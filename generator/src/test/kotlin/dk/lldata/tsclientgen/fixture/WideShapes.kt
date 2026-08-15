package dk.lldata.tsclientgen.fixture

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue

/*
 * Payloads whose only job is to be too wide, kept apart from the fixtures that model a domain.
 *
 * The sweep in `EmittedWidthTest` varies quantities it can build — path depth, parameter count —
 * but a union of literals or a discriminated hierarchy has to be a real declaration for the
 * reflection to read. These are those declarations, sized past the print width on purpose.
 */

/** A union of long literals: the alias is `"…" | "…" | …` and has to break somewhere. */
enum class ReplicationStrategy(
    @JsonValue val wireValue: String,
) {
    SYNCHRONOUS_WITH_QUORUM_ACKNOWLEDGEMENT("synchronous-with-quorum-acknowledgement"),
    ASYNCHRONOUS_WITH_BOUNDED_STALENESS("asynchronous-with-bounded-staleness"),
    ASYNCHRONOUS_WITH_EVENTUAL_CONSISTENCY("asynchronous-with-eventual-consistency"),
    SYNCHRONOUS_WITH_FULL_ACKNOWLEDGEMENT("synchronous-with-full-acknowledgement"),
    LEADER_ONLY_WITH_NO_REPLICATION_AT_ALL("leader-only-with-no-replication-at-all"),
}

/** Members whose types are themselves wide, so the declaration cannot be shortened by wrapping. */
data class WideMemberResponse(
    val strategyPerRegionPerAvailabilityZone: Map<String, Map<String, ReplicationStrategy>>,
    val acknowledgementDeadlinesByReplicaIdentifier: Map<String, List<Long>>,
    val theNameOfTheCurrentlyElectedLeaderReplica: String?,
)

/** A discriminated union whose variant names are long enough to stretch the alias. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "outcome")
@JsonSubTypes(
    JsonSubTypes.Type(value = ReplicationConvergedWithinDeadline::class, name = "converged"),
    JsonSubTypes.Type(value = ReplicationStalledAwaitingQuorum::class, name = "stalled"),
    JsonSubTypes.Type(value = ReplicationAbandonedAfterRepeatedFailures::class, name = "abandoned"),
)
sealed interface ReplicationReport {
    val outcome: String
}

data class ReplicationConvergedWithinDeadline(
    val observedLagInMilliseconds: Long,
) : ReplicationReport {
    override val outcome: String = "converged"
}

data class ReplicationStalledAwaitingQuorum(
    val replicasStillOutstanding: List<String>,
) : ReplicationReport {
    override val outcome: String = "stalled"
}

data class ReplicationAbandonedAfterRepeatedFailures(
    val lastObservedFailureDescription: String,
) : ReplicationReport {
    override val outcome: String = "abandoned"
}
