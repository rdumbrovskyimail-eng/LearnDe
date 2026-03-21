package com.example.test

import android.util.Log

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val age: Int,
    val isActive: Boolean = true,
    val role: String = "user"
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

    fun removeUser(id: Int): Boolean {
        val user = users.find { it.id == id }
        return if (user != null) {
            users.remove(user)
            Log.i("UserManager", "✅ Removed user id=${user.id} name=${user.name}")
            true
        } else {
            Log.e("UserManager", "User not found: $id")
            false
        }
    }

    fun getUserById(id: Int): User? {
        return users.find { it.id == id }
    }

    fun getAllUsers(): List<User> {
        return users.toList()
    }

    fun getActiveUsers(): List<User> {
        return users.filter { it.isActive }
    }

    fun findByName(query: String): List<User> {
        return users.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun updateEmail(id: Int, newEmail: String): Boolean {
        val index = users.indexOfFirst { it.id == id }
        return if (index >= 0) {
            users[index] = users[index].copy(email = newEmail)
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

    fun getUserCount(): Int = users.size

    fun getAverageAge(): Double {
        if (users.isEmpty()) return -1.0
        return users.sumOf { it.age }.toDouble() / users.size
    }

    fun sortByName(): List<User> {
        return users.sortedBy { it.name }
    }

    fun sortByAge(): List<User> {
        return users.sortedBy { it.age }
    }

    fun getUsersByAgeRange(minAge: Int, maxAge: Int): List<User> {
        return users.filter { it.age in minAge..maxAge }
    }

    fun clearAll() {
        users.clear()
        nextId = 1
        Log.i(TAG, "Cleared all users, nextId reset to 1")
    }

    companion object {
        const val TAG = "UserManager"
        const val MAX_USERS = 1000
    }
}