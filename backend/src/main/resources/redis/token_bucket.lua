-- =============================================================================
-- Token bucket, evaluated atomically inside Redis.
--
-- The whole decision -- refill, test, consume, expire -- happens in one script, so
-- concurrent requests cannot interleave between reading the bucket and writing it
-- back. A read-then-write in the application would let N concurrent callers all
-- observe the same remaining count and all be admitted; that is precisely the race
-- a rate limiter exists to prevent, and it is worst under exactly the load that
-- matters.
--
-- The clock is Redis's own (`TIME`), not the caller's. Several API instances share
-- these buckets, and machine clocks drift; taking the time from the one process
-- that owns the state makes the limit independent of the callers' agreement about
-- what time it is. TIME is a non-deterministic command, which is safe here because
-- Redis 7 replicates scripts by their effects rather than by re-running them.
--
-- State is two fields, and the key's TTL is the time the bucket needs to refill to
-- full. A full bucket is indistinguishable from one that never existed, so letting
-- it expire loses nothing and keeps memory proportional to *recent* activity rather
-- than to every identity ever seen.
--
-- KEYS[1] bucket key
-- ARGV[1] capacity, in tokens
-- ARGV[2] refill interval: milliseconds to regenerate one token
-- ARGV[3] cost of this request, in tokens
--
-- returns { allowed (1/0), remaining, retryAfterMs, resetMs }
-- =============================================================================

local capacity   = tonumber(ARGV[1])
local intervalMs = tonumber(ARGV[2])
local cost       = tonumber(ARGV[3])

local time = redis.call('TIME')
local now  = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

local state   = redis.call('HMGET', KEYS[1], 't', 'u')
local tokens  = tonumber(state[1])
local updated = tonumber(state[2])

if tokens == nil or updated == nil then
    -- Unseen identity, or a bucket that refilled and expired: start full.
    tokens = capacity
    updated = now
end

-- Refill for the elapsed time. Guarded against a negative elapsed, which a backwards
-- step of Redis's clock would otherwise turn into tokens being taken away.
local elapsed = now - updated
if elapsed < 0 then
    elapsed = 0
end
tokens = math.min(capacity, tokens + (elapsed / intervalMs))

local allowed = 0
if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
end

-- How long until this request would succeed. Exact for this algorithm: tokens
-- regenerate continuously at a known rate, so the wait is arithmetic rather than a
-- guess. Reported only when the request was refused.
local retryAfterMs = 0
if allowed == 0 then
    retryAfterMs = math.ceil((cost - tokens) * intervalMs)
end

-- How long until at least one whole token is available. Zero while the caller still
-- has one in hand.
local resetMs = 0
if tokens < 1 then
    resetMs = math.ceil((1 - tokens) * intervalMs)
end

-- Deterministic expiry: exactly the time this bucket needs to become full again, at
-- which point it carries no information. Never zero, because PEXPIRE 0 deletes the
-- key we have just written.
local ttlMs = math.ceil((capacity - tokens) * intervalMs)
if ttlMs < 1 then
    ttlMs = 1
end

redis.call('HSET', KEYS[1], 't', tokens, 'u', now)
redis.call('PEXPIRE', KEYS[1], ttlMs)

-- Redis truncates Lua numbers to integers on the way out, so `remaining` is floored
-- deliberately rather than incidentally: reporting 1 remaining when 0.4 of a token
-- is left would promise a request that is about to be refused.
return { allowed, math.floor(tokens), retryAfterMs, resetMs }
