package com.walktracker.app.data.local.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.walktracker.app.model.RoutePoint

class RoutePointConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromRoutePointList(routes: List<RoutePoint>): String {
        return gson.toJson(routes)
    }

    @TypeConverter
    fun toRoutePointList(routesJson: String): List<RoutePoint> {
        val type = object : TypeToken<List<RoutePoint>>() {}.type
        return gson.fromJson(routesJson, type)
    }
}