package com.example.test

import android.util.Log

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val age: Int,
    val isActive: Boolean = true
)

class UserRepository {

    private val users = mutableListOf<User>()
    private var nextId = 1

    fun addUser(name: String, email: String, age: Int): User {
        val user = User(
            id = nextId++,
            name = name,
            email = email,
            age = age
        )
        users.add(user)
        Log.d("UserRepo", "Added user: ${user.name}")
        return user
    }

    fun removeUser(id: Int): Boolean {
        val user = users.find { it.id == id }
        return if (user != null) {
            users.remove(user)
            Log.d("UserRepo", "Removed user: ${user.name}")
            true
        } else {
            Log.w("UserRepo", "User not found: $id")
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

    fun searchByName(query: String): List<User> {
        return users.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun updateEmail(id: Int, newEmail: String): Boolean {
        val index = users.indexOfFirst { it.id == id }
        return if (index >= 0) {
            users[index] = users[index].copy(email = newEmail)
            Log.d("UserRepo", "Updated email for id=$id")
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
        if (users.isEmpty()) return 0.0
        return users.sumOf { it.age }.toDouble() / users.size
    }

    fun sortByName(): List<User> {
        return users.sortedBy { it.name }
    }

    fun sortByAge(): List<User> {
        return users.sortedBy { it.age }
    }

    fun clearAll() {
        users.clear()
        nextId = 1
        Log.d("UserRepo", "All users cleared")
    }

    fun printSummary() {
        Log.d("UserRepo", "=== User Summary ===")
        Log.d("UserRepo", "Total: ${users.size}")
        Log.d("UserRepo", "Active: ${getActiveUsers().size}")
        Log.d("UserRepo", "Average age: ${getAverageAge()}")
    }
}