package com.example

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    val photoUri: String?,
    val audioEmbedding: String // Semicolon and comma separated 2D float sequence of MFCC vectors
) {
    fun getMfccSequence(): List<FloatArray> {
        if (audioEmbedding.isEmpty()) return emptyList()
        return try {
            audioEmbedding.split(";").map { frameStr ->
                frameStr.split(",").map { it.toFloat() }.toFloatArray()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun serializeMfccSequence(sequence: List<FloatArray>): String {
            return sequence.joinToString(";") { frame ->
                frame.joinToString(",")
            }
        }
    }
}
