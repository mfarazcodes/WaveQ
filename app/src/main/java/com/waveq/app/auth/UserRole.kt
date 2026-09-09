package com.waveq.app.auth

enum class UserRole(val label: String) {
    CITIZEN("Citizen"),
    OPERATOR("Operator"),
    ADMIN("Admin"),
}

data class Session(
    val userId: String,
    val displayName: String,
    val role: UserRole,
)

/**
 * Whether a session with this role (null = signed out) may access a route
 * gated to [required] (null = open to everyone). ADMIN satisfies an OPERATOR
 * gate too - it is a superset, not a separate track.
 */
fun UserRole?.satisfies(required: UserRole?): Boolean {
    if (required == null) return true
    if (this == null) return false
    return when (required) {
        UserRole.CITIZEN -> true
        UserRole.OPERATOR -> this == UserRole.OPERATOR || this == UserRole.ADMIN
        UserRole.ADMIN -> this == UserRole.ADMIN
    }
}
