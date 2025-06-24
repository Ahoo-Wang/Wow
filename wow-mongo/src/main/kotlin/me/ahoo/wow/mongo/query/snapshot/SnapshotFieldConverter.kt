package me.ahoo.wow.mongo.query.snapshot

import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.serialization.MessageRecords

object SnapshotFieldConverter : FieldConverter {

    override fun convert(field: String): String {
        if (field == MessageRecords.AGGREGATE_ID) {
            return Documents.ID_FIELD
        }
        return field
    }
}
