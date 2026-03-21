package com.example.test

import android.util.Log

sealed class PagedResult<T> {
    data class Page<T>(
        val items: List<T>,
        val page: Int,
        val pageSize: Int,
        val totalItems: Int
    ) : PagedResult<T>() {
        val totalPages: Int get() = if (totalItems == 0) 0 else (totalItems + pageSize - 1) / pageSize
        val hasNext: Boolean get() = page < totalPages - 1
        val hasPrev: Boolean get() = page > 0
    }
    data class Empty<T>(val message: String) : PagedResult<T>()
}

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

data class UserFilter(
    val query: String? = null,
    val role: String? = null,
    val tag: String? = null,
    val activeOnly: Boolean = false,
    val minAge: Int? = null,
    val maxAge: Int? = null
)

class UserManager {

    private val users = mutableListOf<User>()
    private var nextId = 1
    private val operationLog = mutableListOf<String>()
    private var lastModifiedAt: Long = System.currentTimeMillis()

    private fun applyFilter(filter: UserFilter): List<User> {
        return users.filter { user ->
            (filter.query == null || user.name.contains(filter.query, ignoreCase = true)) &&
            (filter.role == null || user.role.equals(filter.role, ignoreCase = true)) &&
            (filter.tag == null || filter.tag in user.tags) &&
            (!filter.activeOnly || user.isActive) &&
            (filter.minAge == null || user.age >= filter.minAge) &&
            (filter.maxAge == null || user.age <= filter.maxAge)
        }
    }

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
        operationLog.add("REMOVE user id=${user.id} name=${user.name}")
        if (operationLog.size > LOG_CAPACITY) operationLog.removeAt(0)
        lastModifiedAt = System.currentTimeMillis()
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
            operationLog.add("DEACTIVATE user id=$id")
            if (operationLog.size > LOG_CAPACITY) operationLog.removeAt(0)
            lastModifiedAt = System.currentTimeMillis()
            Log.i(TAG, "🚫 Deactivated user id=$id")
            true
        } else {
            Log.e(TAG, "❌ Cannot deactivate: user id=$id not found")
            false
        }
    }

    fun addTag(id: Int, tag: String): UserResult {
        val index = users.indexOfFirst { it.id == id }
        if (index < 0) return UserResult.NotFound(id)
        if (SYSTEM_TAGS.contains(tag) && users[index].role != ADMIN_ROLE) {
            return UserResult.Error("Tag '$tag' requires admin role")
        }
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

    fun getUsersCreatedBetween(fromMs: Long, toMs: Long): List<User> {
        require(fromMs <= toMs) { "fromMs must be <= toMs" }
        return users.filter { it.createdAt in fromMs..toMs }.sortedBy { it.createdAt }
    }

    fun getLastModifiedAt(): Long = lastModifiedAt

    fun getOperationLog(): List<String> = operationLog.toList()

    fun bulkDeactivate(ids: List<Int>): Map<Int, UserResult> {
        return ids.associateWith { id ->
            val index = users.indexOfFirst { it.id == id }
            if (index < 0) {
                UserResult.NotFound(id)
            } else {
                users[index] = users[index].copy(isActive = false)
                operationLog.add("BULK_DEACTIVATE user id=$id")
                if (operationLog.size > LOG_CAPACITY) operationLog.removeAt(0)
                lastModifiedAt = System.currentTimeMillis()
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
        return applyFilter(UserFilter(query = query, role = role, tag = tag, activeOnly = activeOnly))
            .sortedBy { it.name }
    }

    fun searchWithFilter(filter: UserFilter): List<User> {
        return applyFilter(filter).sortedBy { it.name }
    }

    fun getPage(filter: UserFilter, page: Int, pageSize: Int = 20): PagedResult<User> {
        require(page >= 0) { "Page must be >= 0" }
        require(pageSize > 0) { "PageSize must be > 0" }
        val filtered = applyFilter(filter).sortedBy { it.name }
        if (filtered.isEmpty()) return PagedResult.Empty("No users match filter")
        val fromIndex = page * pageSize
        if (fromIndex >= filtered.size) return PagedResult.Empty("Page $page out of range")
        val toIndex = minOf(fromIndex + pageSize, filtered.size)
        return PagedResult.Page(
            items = filtered.subList(fromIndex, toIndex),
            page = page,
            pageSize = pageSize,
            totalItems = filtered.size
        )
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

    fun exportToJson(): String {
        val sb = StringBuilder()
        sb.append("[")
        users.forEachIndexed { index, user ->
            sb.append("{")
            sb.append("\"id\":${user.id},")
            sb.append("\"name\":\"${user.name}\",")
            sb.append("\"email\":\"${user.email}\",")
            sb.append("\"role\":\"${user.role}\",")
            sb.append("\"age\":${user.age},")
            sb.append("\"active\":${user.isActive},")
            sb.append("\"tags\":[${user.tags.joinToString(",") { "\"$it\"" }}],")
            sb.append("\"createdAt\":${user.createdAt}")
            sb.append("}")
            if (index < users.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    fun clearAll() {
        val count = users.size
        users.clear()
        nextId = 100
        operationLog.clear()
        lastModifiedAt = System.currentTimeMillis()
        Log.i(TAG, "🗑️ Cleared $count users, nextId reset to 100")
    }

    fun reactivateUser(id: Int): UserResult {
        val index = users.indexOfFirst { it.id == id }
        if (index < 0) return UserResult.NotFound(id)
        if (users[index].isActive) return UserResult.Error("User id=$id is already active")
        val updated = users[index].copy(isActive = true)
        users[index] = updated
        operationLog.add("REACTIVATE user id=$id")
        if (operationLog.size > LOG_CAPACITY) operationLog.removeAt(0)
        lastModifiedAt = System.currentTimeMillis()
        Log.i(TAG, "✅ Reactivated user id=$id")
        return UserResult.Success(updated)
    }

    fun filterByAgeRange(minAge: Int, maxAge: Int, activeOnly: Boolean = false): List<User> {
        return searchWithFilter(UserFilter(minAge = minAge, maxAge = maxAge, activeOnly = activeOnly))
    }

    fun countByAgeRange(minAge: Int, maxAge: Int, activeOnly: Boolean = false): Int {
        return filterByAgeRange(minAge, maxAge, activeOnly).size
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
        const val DEFAULT_PAGE_SIZE = 20
        val PROTECTED_TAGS = setOf("banned", "suspended")
    }