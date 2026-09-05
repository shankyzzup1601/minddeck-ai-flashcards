-- Separate from existing MindDeck decks. Apply once before enabling native AI.
create table if not exists public.minddeck_native_ai_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  usage_day date not null,
  requests integer not null default 0 check (requests between 0 and 20),
  primary key (user_id, usage_day)
);
alter table public.minddeck_native_ai_usage enable row level security;
revoke all on public.minddeck_native_ai_usage from anon, authenticated;
create or replace function public.consume_native_ai_quota() returns boolean
language plpgsql security definer set search_path = '' as $$
declare allowed integer;
begin
  if auth.uid() is null then return false; end if;
  insert into public.minddeck_native_ai_usage(user_id,usage_day,requests)
  values(auth.uid(),(now() at time zone 'utc')::date,1)
  on conflict(user_id,usage_day) do update set requests=public.minddeck_native_ai_usage.requests+1
    where public.minddeck_native_ai_usage.requests<20
  returning requests into allowed;
  return allowed is not null;
end;
$$;
revoke all on function public.consume_native_ai_quota() from public, anon;
grant execute on function public.consume_native_ai_quota() to authenticated;
