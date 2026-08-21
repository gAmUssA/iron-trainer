-- WHOOP API integration (bean si52): make "the connection is dead" representable.
--
-- WHOOP refresh tokens are single-use and rotate. When one is rejected the athlete
-- must reconnect by hand, but nothing recorded that: WhoopTokens threw a 409, the
-- daily job logged it and moved on, and whoop_refresh_token stayed populated — so
-- every "are we connected?" check still answered yes while the data quietly stopped
-- updating. This column is the missing bit.
--
-- Advisory, not destructive, and deliberately so. A rejected refresh is USUALLY
-- terminal (token spent or revoked), but a WHOOP 5xx or a network blip lands in the
-- same catch. Clearing the stored token there would destroy a working credential
-- over a transient fault. Instead the token is kept and this flag is raised; the
-- next successful refresh clears it, so a transient failure heals itself and only a
-- genuinely dead connection keeps nagging.
ALTER TABLE "public"."athlete"
    ADD COLUMN IF NOT EXISTS "whoop_reconnect_required" boolean;
