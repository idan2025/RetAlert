package network.retalert.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Room type converters for the persistable domain shapes. All collections are
 *  stored as compact JSON via kotlinx.serialization (already a :data dep). */
class Converters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val stringList = ListSerializer(String.serializer())
    private val boolMap = MapSerializer(String.serializer(), Boolean.serializer())

    @TypeConverter
    fun listToString(value: List<String>?): String =
        if (value.isNullOrEmpty()) "[]" else json.encodeToString(stringList, value)

    @TypeConverter
    fun stringToList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(stringList, raw)

    @TypeConverter
    fun mapToString(value: Map<String, Boolean>?): String =
        if (value.isNullOrEmpty()) "{}" else json.encodeToString(boolMap, value)

    @TypeConverter
    fun stringToMap(raw: String?): Map<String, Boolean> =
        if (raw.isNullOrBlank()) emptyMap() else json.decodeFromString(boolMap, raw)
}