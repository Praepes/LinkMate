package io.linkmate.data.repository

import io.linkmate.data.local.dao.ReminderDao
import io.linkmate.data.local.ReminderEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepository @Inject constructor(
    private val reminderDao: ReminderDao
) {
    fun getAllReminders(): Flow<List<ReminderEntity>> {
        return reminderDao.getAllReminders()
    }

    suspend fun insertReminder(reminder: ReminderEntity) {
        reminderDao.insertReminder(reminder)
    }

    suspend fun deleteReminder(reminder: ReminderEntity) {
        reminderDao.deleteReminder(reminder)
    }

    suspend fun updateReminderActiveStatus(reminderId: Int, isActive: Boolean) {
        reminderDao.updateReminderActiveStatus(reminderId, isActive)
    }

    suspend fun deleteReminderById(reminderId: Int) {
        reminderDao.deleteReminderById(reminderId)
    }
}