package com.example.test

import android.util.Log

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
)

class UserManager {

    private val users = mutableListOf<User>()
    private var nextId = 1

    fun addUser(name: String, email: String, age: Int, role: String = "user"): User {
        val user = User(
            id = nextId++,
            name = name,
            email = email,
            age = age,
            role = role
        )
        users.add(user)
        Log.i("UserManager", "Added user: ${user.name}")
        return user
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

    fun updateUserEmail(id: Int, newEmail: String): Boolean {
        val index = users.indexOfFirst { it.id == id }
        return if (index >= 0) {
            val oldEmail = users[index].email
            users[index] = users[index].copy(email = newEmail)
            Log.i(TAG, "📧 Email changed for id=$id: $oldEmail → $newEmail")
            Log.i("UserManager", "Updated email for id=$id")
            true
        } else {
            false
        }
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

    fun getUsersByTag(tag: String): List<User> {
        return users.filter { tag in it.tags }
    }

    fun getUserCount(): Int = users.size

    fun getAverageAge(): Double {
        if (users.isEmpty()) return -1.0
        return users.sumOf { it.age }.toDouble() / users.size
    }

    fun sortByAge(): List<User> {
        return users.sortedBy { it.age }
    }

    fun getUsersByAgeRange(minAge: Int, maxAge: Int): List<User> {
        return users.filter { it.age in minAge..maxAge }
    }

    fun exportToMap(): Map<Int, String> {
        return users.associate { it.id to "${it.name} <${it.email}> [${it.role}]" }
    }

    fun clearAll() {
        users.clear()
        nextId = 100
        Log.i(TAG, "Cleared all users, nextId reset to 1")
    }

    companion object {
        const val TAG = "UserManager"
        const val MAX_USERS = 1000
        const val DEFAULT_ROLE = "user"
        const val ADMIN_ROLE = "admin"
        val SYSTEM_TAGS = listOf("verified", "premium", "banned")
    }
}