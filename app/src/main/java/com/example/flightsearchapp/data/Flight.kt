package com.example.flightsearchapp.data

data class Flight(
    val departureAirport: Airport,
    val destinationAirport: Airport,
    var isFavorite: Boolean = false
) 