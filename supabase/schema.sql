-- Run once in the Supabase SQL Editor for the MindDeck project.
-- Each authenticated user can access only their own deck row.

create table if not exists public.minddeck_decks (
  user_id uuid primary key references auth.users (id) on delete cascade,
  deck jsonb not null default '{"version":3,"cards":[],"index":0,"reviewed":[],"updatedAt":0}'::jsonb,
  updated_at timestamptz not null default timezone('utc'::text, now()),
  constraint minddeck_deck_is_object check (jsonb_typeof(deck) = 'object'),
  constraint minddeck_deck_size check (pg_column_size(deck) <= 262144)
);

alter table public.minddeck_decks enable row level security;
alter table public.minddeck_decks force row level security;

revoke all on table public.minddeck_decks from anon, authenticated;
grant usage on schema public to authenticated;
grant select, insert, update, delete on table public.minddeck_decks to authenticated;

drop policy if exists "minddeck_select_own" on public.minddeck_decks;
create policy "minddeck_select_own"
on public.minddeck_decks
for select
to authenticated
using ((select auth.uid()) is not null and (select auth.uid()) = user_id);

drop policy if exists "minddeck_insert_own" on public.minddeck_decks;
create policy "minddeck_insert_own"
on public.minddeck_decks
for insert
to authenticated
with check ((select auth.uid()) is not null and (select auth.uid()) = user_id);

drop policy if exists "minddeck_update_own" on public.minddeck_decks;
create policy "minddeck_update_own"
on public.minddeck_decks
for update
to authenticated
using ((select auth.uid()) is not null and (select auth.uid()) = user_id)
with check ((select auth.uid()) is not null and (select auth.uid()) = user_id);

drop policy if exists "minddeck_delete_own" on public.minddeck_decks;
create policy "minddeck_delete_own"
on public.minddeck_decks
for delete
to authenticated
using ((select auth.uid()) is not null and (select auth.uid()) = user_id);

