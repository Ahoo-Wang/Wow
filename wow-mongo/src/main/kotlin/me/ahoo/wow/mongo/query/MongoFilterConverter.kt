package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.bson.conversions.Bson

object MongoFilterConverter {

    fun Projection.toMongoProjection(): Bson? {
        if (isEmpty()) return null
        if (include.isNotEmpty() && exclude.isNotEmpty()) {
            return Projections.fields(Projections.include(include), Projections.exclude(exclude))
        }
        if (include.isNotEmpty()) {
            return Projections.include(include)
        }
        return Projections.exclude(exclude)
    }

    fun List<Sort>.toMongoSort(): Bson? {
        if (isEmpty()) return null
        return map {
            when (it.direction) {
                Sort.Direction.ASC -> Sorts.ascending(it.field)
                Sort.Direction.DESC -> Sorts.descending(it.field)
            }
        }.toList().let {
            Sorts.orderBy(it)
        }
    }
}
