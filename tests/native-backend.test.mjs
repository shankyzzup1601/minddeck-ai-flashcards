import test from 'node:test';
import assert from 'node:assert/strict';
import handler from '../api/minddeck-native.mjs';

function invoke(body,headers={},method='POST') {
  const response={code:0,body:null,setHeader(){},status(n){this.code=n;return this;},json(data){this.body=data;return this;}};
  return handler({method,body,headers:{'content-type':'application/json',...headers}},response).then(()=>response);
}
const env={...process.env};
test.beforeEach(()=>{process.env.SUPABASE_URL='https://example.supabase.co';process.env.SUPABASE_PUBLISHABLE_KEY='public-test-key-01234567890123456789';});
test.afterEach(()=>{process.env={...env};});
test('missing and forged bearer tokens cannot reach AI',async()=>{
  const original=globalThis.fetch;
  let calls=0;
  globalThis.fetch=async()=>{calls++;return new Response('{}',{status:401});};
  try {
    assert.equal((await invoke({action:'generate'})).code,401);
    assert.equal(calls,0);
    assert.equal((await invoke({action:'generate'},{authorization:'Bearer '+'x'.repeat(50)})).code,401);
    assert.equal(calls,1);
  } finally {globalThis.fetch=original;}
});
test('Google ID tokens and nonce are verified upstream; failure returns no session',async()=>{
  const original=globalThis.fetch;
  globalThis.fetch=async(url,options)=>{
    assert.match(url,/grant_type=id_token/);
    const body=JSON.parse(options.body);assert.equal(body.provider,'google');assert.equal(body.nonce,'n'.repeat(64));
    return new Response('{}',{status:400});
  };
  try {const result=await invoke({action:'signIn',idToken:'t'.repeat(150),nonce:'n'.repeat(64)});assert.equal(result.code,401);assert.equal(result.body.access_token,undefined);}
  finally {globalThis.fetch=original;}
});
test('valid Supabase sessions with 12-character refresh tokens are accepted',async()=>{
  const original=globalThis.fetch;
  globalThis.fetch=async()=>new Response(JSON.stringify({
    access_token:'a'.repeat(64),
    refresh_token:'r'.repeat(12),
    user:{id:'test-user',user_metadata:{name:'Student'}}
  }),{status:200});
  try {
    const result=await invoke({action:'signIn',idToken:'t'.repeat(150),nonce:'n'.repeat(64)});
    assert.equal(result.code,200);
    assert.equal(result.body.refresh_token.length,12);
    assert.equal(result.body.user.id,'test-user');
  } finally {globalThis.fetch=original;}
});
test('oversized notes are refused before quota or AI requests',async()=>{
  const original=globalThis.fetch;let calls=0;
  globalThis.fetch=async()=>{calls++;return new Response(JSON.stringify({id:'test-user'}),{status:200});};
  try {const result=await invoke({action:'generate',subject:'Physics',classLevel:'Class 12',chapter:'Electric Charges',notes:'n'.repeat(12001)},{authorization:'Bearer '+'x'.repeat(50)});assert.equal(result.code,400);assert.equal(calls,1);}
  finally {globalThis.fetch=original;}
});
test('quota setup failure fails closed and never sends study notes to AI',async()=>{
  const original=globalThis.fetch;let calls=0;
  globalThis.fetch=async(url)=>{calls++;return url.endsWith('/auth/v1/user')?new Response(JSON.stringify({id:'test-user'}),{status:200}):new Response('{}',{status:404});};
  try {const result=await invoke({action:'generate',subject:'Physics',classLevel:'Class 12',chapter:'Electric Charges'},{authorization:'Bearer '+'x'.repeat(50)});assert.equal(result.code,503);assert.equal(calls,2);assert.match(result.body.error,/migration/);}
  finally {globalThis.fetch=original;}
});
test('exhausted quota never attempts model generation',async()=>{
  const original=globalThis.fetch;let calls=0;
  globalThis.fetch=async(url)=>{calls++;return url.endsWith('/auth/v1/user')?new Response(JSON.stringify({id:'test-user'}),{status:200}):new Response('false',{status:200});};
  try {const result=await invoke({action:'generate',subject:'Biology',classLevel:'Class 11',chapter:'The Living World'},{authorization:'Bearer '+'x'.repeat(50)});assert.equal(result.code,429);assert.equal(calls,2);}
  finally {globalThis.fetch=original;}
});
test('public configuration contains no provider or account secrets',async()=>{
  process.env.AI_GATEWAY_API_KEY='private-key-do-not-return';
  const result=await invoke(null,{},'GET');assert.equal(result.code,200);assert.deepEqual(Object.keys(result.body).sort(),['googleClientId','version']);assert.doesNotMatch(JSON.stringify(result.body),/private-key/);
});
test('provider refusal is surfaced without exposing provider credentials',async()=>{
  process.env.AI_GATEWAY_API_KEY='test-private-ai-key';
  const original=globalThis.fetch;
  globalThis.fetch=async(url)=> url.endsWith('/auth/v1/user')?new Response(JSON.stringify({id:'test-user'}),{status:200}):url.includes('/rpc/')?new Response('true',{status:200}):new Response(JSON.stringify({error:{message:'secret provider diagnostics'}}),{status:403});
  try {const result=await invoke({action:'generate',subject:'Physics',classLevel:'Class 12',chapter:'Electric Charges'},{authorization:'Bearer '+'x'.repeat(50)});assert.equal(result.code,503);assert.match(result.body.error,/refused access/);assert.doesNotMatch(JSON.stringify(result.body),/test-private|secret provider/);}
  finally {globalThis.fetch=original;}
});
test('direct Gemini key stays server-side and returns structured cards',async()=>{
  process.env.GEMINI_API_KEY='server-only-gemini-key';
  const original=globalThis.fetch;
  globalThis.fetch=async(url,options)=>{
    if(url.endsWith('/auth/v1/user')) return new Response(JSON.stringify({id:'test-user'}),{status:200});
    if(url.includes('/rpc/')) return new Response('true',{status:200});
    assert.match(url,/generativelanguage\.googleapis\.com/);
    assert.equal(options.headers['x-goog-api-key'],'server-only-gemini-key');
    assert.doesNotMatch(url,/server-only-gemini-key/);
    return new Response(JSON.stringify({candidates:[{content:{parts:[{text:JSON.stringify({cards:[{front:'What is a semiconductor?',back:'A material with conductivity between a conductor and an insulator.'}]})}]}}]}),{status:200});
  };
  try {
    const result=await invoke({action:'generate',subject:'Physics',classLevel:'Class 12',chapter:'Semiconductor Electronics'},{authorization:'Bearer '+'x'.repeat(50)});
    assert.equal(result.code,200);
    assert.equal(result.body.cards.length,1);
    assert.doesNotMatch(JSON.stringify(result.body),/server-only-gemini-key/);
  } finally {globalThis.fetch=original;}
});
test('malformed generated cards are rejected rather than replacing a deck',async()=>{
  process.env.AI_GATEWAY_API_KEY='test-private-ai-key';
  const original=globalThis.fetch;
  globalThis.fetch=async(url)=> url.endsWith('/auth/v1/user')?new Response(JSON.stringify({id:'test-user'}),{status:200}):url.includes('/rpc/')?new Response('true',{status:200}):new Response(JSON.stringify({choices:[{message:{content:'{"cards":[{"front":"","back":""}]}'}}]}),{status:200});
  try {const result=await invoke({action:'generate',subject:'Physics',classLevel:'Class 12',chapter:'Electric Charges'},{authorization:'Bearer '+'x'.repeat(50)});assert.equal(result.code,502);assert.equal(result.body.cards,undefined);}
  finally {globalThis.fetch=original;}
});
test('simulated successful AI response returns only valid study content',async()=>{
  process.env.AI_GATEWAY_API_KEY='test-private-ai-key';
  const original=globalThis.fetch;
  globalThis.fetch=async(url)=> url.endsWith('/auth/v1/user')?new Response(JSON.stringify({id:'test-user'}),{status:200}):url.includes('/rpc/')?new Response('true',{status:200}):new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({cards:[{front:'What is electric charge?',back:'A physical property responsible for electrical interactions.',ignored:'untrusted'}]})}}]}),{status:200});
  try {const result=await invoke({action:'generate',subject:'Physics',classLevel:'Class 12',chapter:'Electric Charges'},{authorization:'Bearer '+'x'.repeat(50)});assert.equal(result.code,200);assert.deepEqual(Object.keys(result.body.cards[0]).sort(),['back','front']);}
  finally {globalThis.fetch=original;}
});
