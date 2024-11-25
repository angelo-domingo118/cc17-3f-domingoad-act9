package com.example.flightsearchapp
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import com.example.flightsearchapp.data.Airport
import com.example.flightsearchapp.data.Favorite
import com.example.flightsearchapp.data.Flight
import com.example.flightsearchapp.data.FlightDatabase
import com.example.flightsearchapp.data.AirportRepository
import com.example.flightsearchapp.data.FavoriteRepository
import com.example.flightsearchapp.ui.AirportSuggestionAdapter
import com.example.flightsearchapp.ui.FlightAdapter
import com.example.flightsearchapp.ui.FavoriteAdapter
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import com.example.flightsearchapp.data.PreferencesManager
import com.example.flightsearchapp.databinding.ItemAirportSuggestionBinding
import com.example.flightsearchapp.databinding.ItemFlightBinding
import com.example.flightsearchapp.databinding.ItemFavoriteRouteBinding
import android.util.Log
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var searchEditText: TextInputEditText
    private lateinit var airportAdapter: AirportSuggestionAdapter
    private lateinit var airportRepository: AirportRepository
    private lateinit var flightAdapter: FlightAdapter
    private lateinit var favoriteRepository: FavoriteRepository
    private val searchJob = Job()
    private val searchScope = CoroutineScope(Dispatchers.Main + searchJob)
    private val flightJob = Job()
    private val flightScope = CoroutineScope(Dispatchers.Main + flightJob)
    private lateinit var favoriteAdapter: FavoriteAdapter
    private val favoriteJob = Job()
    private val favoriteScope = CoroutineScope(Dispatchers.Main + favoriteJob)
    private var currentScrollPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupDependencies()
        setupRecyclerView()
        setupSearchView()
        setupFlightRecyclerView()
        setupFavorites()
        restoreAppState()
    }

    private fun setupDependencies() {
        try {
            Log.d("MainActivity", "Initializing dependencies")
            preferencesManager = PreferencesManager(this)
            val database = FlightDatabase.getDatabase(this)
            Log.d("MainActivity", "Database initialized")
            
            airportRepository = AirportRepository(database.airportDao())
            favoriteRepository = FavoriteRepository(database.favoriteDao())
            Log.d("MainActivity", "Repositories initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing dependencies", e)
            Toast.makeText(
                this,
                "Error initializing app: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.search_results)
        airportAdapter = AirportSuggestionAdapter { airport ->
            handleAirportSelection(airport)
        }
        recyclerView.apply {
            adapter = airportAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(
                DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            )
        }
    }

    private fun setupSearchView() {
        searchEditText = findViewById(R.id.airport_search)
        
        // Restore saved search query
        lifecycleScope.launch {
            preferencesManager.searchQuery.collect { savedQuery ->
                if (searchEditText.text.toString() != savedQuery) {
                    searchEditText.setText(savedQuery)
                }
            }
        }

        // Setup search text watcher
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchAirports(s?.toString() ?: "")
            }
        })
    }

    private fun searchAirports(query: String) {
        // Cancel previous search job
        searchScope.cancel()
        
        if (query.isBlank()) {
            Log.d("MainActivity", "Empty query, showing favorites")
            showFavorites()
            return
        }

        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Searching for query: $query")
                airportRepository.searchAirports(query).collect { airports ->
                    Log.d("MainActivity", "Found ${airports.size} airports")
                    if (airports.isEmpty()) {
                        Log.d("MainActivity", "No airports found")
                        showEmptyState()
                    } else {
                        hideEmptyState()
                        airportAdapter.submitList(airports)
                        findViewById<RecyclerView>(R.id.search_results).adapter = 
                            airportAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error searching airports", e)
            }
        }
    }

    private fun showEmptyState() {
        findViewById<TextView>(R.id.empty_state).apply {
            visibility = View.VISIBLE
            text = getString(R.string.no_results)
        }
        findViewById<RecyclerView>(R.id.search_results).visibility = View.GONE
    }

    private fun hideEmptyState() {
        findViewById<TextView>(R.id.empty_state).visibility = View.GONE
        findViewById<RecyclerView>(R.id.search_results).visibility = View.VISIBLE
    }

    private fun handleAirportSelection(airport: Airport) {
        // Save the selected airport code
        lifecycleScope.launch {
            preferencesManager.saveSearchQuery(airport.iataCode)
        }
        // Query for available flights
        searchScope.launch {
            airportRepository.getDestinationAirports(airport.iataCode)
                .collect { destinations ->
                    // Switch to flight results adapter
                    showFlightResults(airport, destinations)
                }
        }
    }

    private fun showFavorites() {
        favoriteScope.launch {
            favoriteRepository.getAllFavorites().collect { favorites ->
                if (favorites.isEmpty()) {
                    showEmptyFavorites()
                } else {
                    favoriteAdapter.submitList(favorites)
                    findViewById<RecyclerView>(R.id.search_results).adapter = 
                        favoriteAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
                }
            }
        }
    }

    private fun showEmptyFavorites() {
        // Show empty state message
        findViewById<TextView>(R.id.empty_state).apply {
            visibility = View.VISIBLE
            text = getString(R.string.no_favorites)
        }
    }

    private fun handleFavoriteDelete(favorite: Favorite) {
        lifecycleScope.launch {
            favoriteRepository.removeFavorite(favorite)
        }
    }

    private fun showFlightResults(departure: Airport, destinations: List<Airport>) {
        flightScope.launch {
            val flights = destinations.map { destination ->
                Flight(departure, destination).also { flight ->
                    favoriteRepository.isRouteFavorite(
                        flight.departureAirport.iataCode,
                        flight.destinationAirport.iataCode
                    ).collect { isFavorite ->
                        flight.isFavorite = isFavorite
                        flightAdapter.notifyItemChanged(flightAdapter.currentList.indexOf(flight))
                    }
                }
            }
            flightAdapter.submitList(flights)
        }
    }

    private fun setupFlightRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.search_results)
        flightAdapter = FlightAdapter { flight ->
            handleFavoriteClick(flight)
        }
        recyclerView.apply {
            adapter = flightAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(
                DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            )
        }
    }

    private fun handleFavoriteClick(flight: Flight) {
        lifecycleScope.launch {
            favoriteRepository.isRouteFavorite(
                flight.departureAirport.iataCode,
                flight.destinationAirport.iataCode
            ).collect { isFavorite ->
                if (isFavorite) {
                    val favorite = favoriteRepository.getFavorite(
                        flight.departureAirport.iataCode,
                        flight.destinationAirport.iataCode
                    )
                    favorite?.let { favoriteRepository.removeFavorite(it) }
                } else {
                    favoriteRepository.addFavorite(
                        Favorite(
                            departureCode = flight.departureAirport.iataCode,
                            destinationCode = flight.destinationAirport.iataCode
                        )
                    )
                }
            }
        }
    }

    private fun setupFavorites() {
        favoriteAdapter = FavoriteAdapter { favorite ->
            handleFavoriteDelete(favorite)
        }
        
        // Observe favorites when search is empty
        lifecycleScope.launch {
            searchEditText.text?.let { searchText ->
                if (searchText.isEmpty()) {
                    showFavorites()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob.cancel()
        flightJob.cancel()
        favoriteJob.cancel()
    }

    override fun onPause() {
        super.onPause()
        saveAppState()
    }

    private fun saveAppState() {
        lifecycleScope.launch {
            val recyclerView = findViewById<RecyclerView>(R.id.search_results)
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager
            currentScrollPosition = layoutManager.findFirstVisibleItemPosition()
            
            preferencesManager.apply {
                saveSearchQuery(searchEditText.text?.toString() ?: "")
                saveScrollPosition(currentScrollPosition)
            }
        }
    }

    private fun restoreAppState() {
        lifecycleScope.launch {
            // Restore scroll position
            preferencesManager.scrollPosition.collect { position ->
                val recyclerView = findViewById<RecyclerView>(R.id.search_results)
                (recyclerView.layoutManager as LinearLayoutManager)
                    .scrollToPosition(position)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("SCROLL_POSITION", currentScrollPosition)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentScrollPosition = savedInstanceState.getInt("SCROLL_POSITION", 0)
        val recyclerView = findViewById<RecyclerView>(R.id.search_results)
        recyclerView.layoutManager?.scrollToPosition(currentScrollPosition)
    }
}