package com.yangsenunbam.session

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.FinishReason

/** BE2 owns feed data used by the BE1 session lifecycle in the same transaction. */
interface FeedPreparationPort {
    fun prepare(userId: String, nightId: String, sessionId: String, sheepCount: Int): Int
    fun canFinishStopPause(sessionId: String, position: Int): Boolean
    fun countSavedFluff(nightId: String, sessionId: String, furthestPosition: Int): Int
}

/** BE2 + BE3 provide an approved closing text card; no placeholder is emitted in production. */
interface ClosingCardPort {
    fun choose(userId: String, nightId: String, sessionId: String, reason: FinishReason): CardResponse
}

/** BE3 adapter must use a current locking read in the caller's transaction against the
 * same card row/tombstone locked by deletion. Missing/deleted/unapproved => false;
 * lookup failure => throw 503, NEVER fall back to the saved content. No cache/snapshot read.
 * Lock order: account -> session/night -> card; deletion must not acquire reader accounts.
 */
interface ClosingCardVisibilityPort { fun lockAndCheckAvailable(cardId: String): Boolean }

/** BE2 provides the existence of adoptable-but-not-adopted candidates for bootstrap daytime routing. */
interface MorningCandidatePort { fun hasCandidate(userId: String, nightId: String): Boolean }
