package com.example.test

import android.util.Log

class UserRepository(
    private val database: LocalDatabase,
    private val apiService: ApiService
) {

    companion object {
        private const val TAG = "UserRepository"
        private const val CACHE_TIMEOUT = 30_000L
        private const val MAX_RETRY = 3
    }

    private var lastFetchTime = 0L
    private val userCache = mutableMapOf<String, User>()

    suspend fun getUser(userId: String): Result<User> {
        val cached = userCache[userId]
        if (cached != null && !isCacheExpired()) {
            Log.d(TAG, "Cache hit for user: $userId")
            return Result.success(cached)
        }

        return fetchFromRemote(userId)
    }

    suspend fun getUserList(page: Int, pageSize: Int = 20): Result<List<User>> {
        if (page < 0) return Result.failure(IllegalArgumentException("Page must be >= 0"))
        if (pageSize > 100) return Result.failure(IllegalArgumentException("Page size too large"))

        return try {
            val users = apiService.fetchUsers(page, pageSize)
            users.forEach { userCache[it.id] = it }
            lastFetchTime = System.currentTimeMillis()
            Log.d(TAG, "Fetched ${users.size} users from remote")
            Result.success(users)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch users", e)
            Result.failure(e)
        }
    }

    suspend fun createUser(name: String, email: String): Result<User> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be blank"))
        if (!email.contains("@")) return Result.failure(IllegalArgumentException("Invalid email"))

        return try {
            val user = apiService.createUser(name, email)
            userCache[user.id] = user
            database.insertUser(user)
            Log.d(TAG, "Created user: ${user.id}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create user", e)
            Result.failure(e)
        }
    }

    suspend fun updateUser(userId: String, name: String?, email: String?): Result<User> {
        val existing = userCache[userId]
            ?: database.getUser(userId)
            ?: return Result.failure(NoSuchElementException("User not found: $userId"))

        val updated = existing.copy(
            name = name ?: existing.name,
            email = email ?: existing.email
        )

        return try {
            val saved = apiService.updateUser(updated)
            userCache[userId] = saved
            database.updateUser(saved)
            Log.d(TAG, "Updated user: $userId")
            Result.success(saved)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update user", e)
            Result.failure(e)
        }
    }

    suspend fun deleteUser(userId: String): Result<Unit> {
        return try {
            apiService.deleteUser(userId)
            userCache.remove(userId)
            database.deleteUser(userId)
            Log.d(TAG, "Deleted user: $userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete user", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchFromRemote(userId: String): Result<User> {
        repeat(MAX_RETRY) { attempt ->
            try {
                val user = apiService.fetchUser(userId)
                userCache[userId] = user
                lastFetchTime = System.currentTimeMillis()
                return Result.success(user)
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed for user $userId")
                if (attempt == MAX_RETRY - 1) return Result.failure(e)
            }
        }
        return Result.failure(Exception("Max retries exceeded"))
    }

    fun clearCache() {
        userCache.clear()
        lastFetchTime = 0L
        Log.d(TAG, "Cache cleared")
    }

    private fun isCacheExpired(): Boolean {
        return System.currentTimeMillis() - lastFetchTime > CACHE_TIMEOUT
    }
}

data class User(
    val id: String,
    val name: String,
    val email: String,
    val createdAt: Long = System.currentTimeMillis()
)