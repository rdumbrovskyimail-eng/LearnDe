package com.example.test

import android.util.Log

interface Exportable {
    fun toExportString(): String
}

sealed class SortOrder {
    object ByName : SortOrder()
    object ByAge : SortOrder()
    object ByCreatedAt : SortOrder()
    data class ByTag(val tag: String) : SortOrder()
}

sealed class UserResult {
    data class Success(val user: User) : UserResult()
    data class NotFound(val id: Int) : UserResult()
    data class Error(val message: String) : UserResult()
}

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val age: Int,
    val isActive: Boolean = true,
    val role: String = "user",
    val createdAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
) : Exportable {
    override fun toExportString(): String {
        return "[$id] $name <$email> role=$role age=$age active=$isActive tags=${tags.joinToString(",")}"
    }
}

data class UserStats(
    val totalUsers: Int,
    val activeUsers: Int,
    val averageAge: Double,
    val mostCommonRole: String,
    val newestUserId: Int?
)

class UserManager {

    private val users = mutableListOf<User>()
    private var nextId = 1
    private val operationLog = mutableListOf<String>()
    private var lastModifiedAt: Long = System.currentTimeMillis()

    fun addUser(name: String, email: String, age: Int, role: String = DEFAULT_ROLE, tags: List<String> = emptyList()): UserResult {
        if (users.size >= MAX_USERS) return UserResult.Error("Max users limit reached: $MAX_USERS")
        if (name.isBlank()) return UserResult.Error("Name cannot be blank")
        if (!email.contains("@")) return UserResult.Error("Invalid email: $email")
        val user = User(id = nextId++, name = name.trim(), email = email.trim(), age = age, role = role, tags = tags)
        users.add(user)
        lastModifiedAt = System.currentTimeMillis()
        operationLog.add("ADD user id=${user.id} name=${user.name}")
        if (operationLog.size > LOG_CAPACITY) operationLog.removeAt(0)
        Log.i(TAG, "✅ Added user: ${user.name} role=${user.role}")
        return UserResult.Success(user)
    }

    fun removeUser(id: Int): UserResult {
        val user = users.find { it.id == id } ?: return UserResult.NotFound(id)
        users.remove(user)
        Log.i(TAG, "✅ Removed user id=${user.id} name=${user.name}")
        return UserResult.Success(user)
    }

    fun getUserById(id: Int): UserResult {
        val user = users.find { it.id == id }
        return if (user != null) UserResult.Success(user) else UserResult.NotFound(id)
    }

    fun getAllUsers(): List<User> {
        return users.toList()
    }

    fun getActiveUsers(): List<User> {
        return users.filter { it.isActive }.sortedBy { it.name }
    }

    fun findByName(query: String): List<User> {
        return users.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun findByRole(role: String): List<User> {
        return users.filter { it.role.equals(role, ignoreCase = true) }
    }

    fun promoteToAdmin(id: Int): UserResult {
        val index = users.indexOfFirst { it.id == id }
        if (index < 0) return UserResult.NotFound(id)
        if (users[index].role == ADMIN_ROLE) return UserResult.Error("User id=$id is already admin")
        val updated = users[index].copy(role = ADMIN_ROLE)
        users[index] = updated
        operationLog.add("PROMOTE user id=$id to admin")
        if (operationLog.size > LOG_CAPACITY) operationLog.removeAt(0)
        lastModifiedAt = System.currentTimeMillis()
        Log.i(TAG, "👑 User id=$id promoted to admin")
        return UserResult.Success(updated)
    }

    fun updateUserEmail(id: Int, newEmail: String): Boolean {
        val index = users.indexOfFirst { it.id == id }
        return if (index >= 0) {
            val oldEmail = users[index].email
            users[index] = users[index].copy(email = newEmail)
            Log.i(TAG, "📧 Email changed for id=$id: $oldEmail → $newEmail")
            true
        } else {
            false
        }
    }

    fun bulkAddTags(tag: String, userIds: List<Int>): Map<Int, UserResult> {
        return userIds.associateWith { addTag(it, tag) }
    }

    fun deactivateUser(id: Int): Boolean {
        val index = users.indexOfFirst { it.id == id }
        return if (index >= 0) {
            users[index] = users[index].copy(isActive = false)
            true
        } else {
            false
        }
    }

    fun addTag(id: Int, tag: String): UserResult {
        val index = users.indexOfFirst { it.id == id }
        if (index < 0) return UserResult.NotFound(id)
        val updated = users[index].copy(tags = users[index].tags + tag)
        users[index] = updated
        Log.i(TAG, "🏷️ Tag '$tag' added to user id=$id")
        return UserResult.Success(updated)
    }

    fun getUserCount(): Int = users.size

    fun getUsersByTags(tags: List<String>, matchAll: Boolean = false): List<User> {
        return users.filter { user ->
            if (matchAll) tags.all { it in user.tags }
            else tags.any { it in user.tags }
        }
    }

    fun getAverageAge(activeOnly: Boolean = false): Double {
        val target = if (activeOnly) users.filter { it.isActive } else users
        if (target.isEmpty()) return -1.0
        return target.sumOf { it.age }.toDouble() / target.size
    }

    fun getSortedUsers(order: SortOrder): List<User> {
        return when (order) {
            is SortOrder.ByName -> users.sortedBy { it.name }
            is SortOrder.ByAge -> users.sortedBy { it.age }
            is SortOrder.ByCreatedAt -> users.sortedBy { it.createdAt }
            is SortOrder.ByTag -> users.filter { order.tag in it.tags }.sortedBy { it.name }
        }
    }

    fun getStats(): UserStats {
        val mostCommonRole = users.groupBy { it.role }
            .maxByOrNull { it.value.size }?.key ?: DEFAULT_ROLE
        val newestUserId = users.maxByOrNull { it.createdAt }?.id
        return UserStats(
            totalUsers = users.size,
            activeUsers = getActiveUsers().size,
            averageAge = getAverageAge(activeOnly = true),
            mostCommonRole = mostCommonRole,
            newestUserId = newestUserId
        )
    }

    fun getAdmins(): List<User> {
        return findByRole(ADMIN_ROLE)
    }

    fun getOperationLog(): List<String> = operationLog.toList()

    fun bulkDeactivate(ids: List<Int>): Map<Int, UserResult> {
        return ids.associateWith { id ->
            val index = users.indexOfFirst { it.id == id }
            if (index < 0) {
                UserResult.NotFound(id)
            } else {
                users[index] = users[index].copy(isActive = false)
                operationLog.add("DEACTIVATE user id=$id")
                UserResult.Success(users[index])
            }
        }
    }

    fun bulkRemove(ids: List<Int>): Map<Int, UserResult> {
        return ids.associateWith { removeUser(it) }
    }

    fun searchUsers(
        query: String? = null,
        role: String? = null,
        tag: String? = null,
        activeOnly: Boolean = false
    ): List<User> {
        return users.filter { user ->
            (query == null || user.name.contains(query, ignoreCase = true)) &&
            (role == null || user.role.equals(role, ignoreCase = true)) &&
            (tag == null || tag in user.tags) &&
            (!activeOnly || user.isActive)
        }.sortedBy { it.name }
    }

    fun exportToMap(): Map<Int, Map<String, Any>> {
        return users.associate { user ->
            user.id to mapOf(
                "name" to user.name,
                "email" to user.email,
                "role" to user.role,
                "age" to user.age,
                "active" to user.isActive,
                "tags" to user.tags,
                "createdAt" to user.createdAt
            )
        }
    }

    fun clearAll() {
        val count = users.size
        users.clear()
        nextId = 100
        operationLog.clear()
        lastModifiedAt = System.currentTimeMillis()
        Log.i(TAG, "🗑️ Cleared $count users, nextId reset to 100")
    }

    fun filterByAgeRange(minAge: Int, maxAge: Int, activeOnly: Boolean = false): List<User> {
        val target = if (activeOnly) users.filter { it.isActive } else users
        return target.filter { it.age in minAge..maxAge }
    }

    companion object {
        const val TAG = "UserManager"
        const val MAX_USERS = 1000
        const val DEFAULT_ROLE = "user"
        const val ADMIN_ROLE = "admin"
        val SYSTEM_TAGS = listOf("verified", "premium", "banned")
        val VALID_ROLES = setOf(DEFAULT_ROLE, ADMIN_ROLE, "moderator", "guest")
        const val MIN_AGE = 0
        const val MAX_AGE = 150
        const val LOG_CAPACITY = 500
    }