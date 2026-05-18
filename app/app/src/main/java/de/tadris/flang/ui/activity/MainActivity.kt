package de.tadris.flang.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.WorkerThread
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.navigation.NavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import de.tadris.flang.R
import de.tadris.flang.network.CredentialsStorage
import de.tadris.flang.network.DataRepository
import de.tadris.flang.network_api.exception.ForbiddenException
import de.tadris.flang.ui.view.addTopPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Exception

class MainActivity : AppCompatActivity() {

    private lateinit var navView: NavigationView
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var headerUsernameText: TextView
    private lateinit var headerRatingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val fab: FloatingActionButton = findViewById(R.id.fab)
        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        val navController = findNavController(R.id.nav_host_fragment)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(setOf(R.id.nav_home, R.id.nav_profile, R.id.nav_offline_game, R.id.nav_tv, R.id.nav_chat, R.id.nav_top, R.id.nav_play_over_board, R.id.nav_settings, R.id.nav_next), drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
        
        // Set up navigation listener to intercept puzzle navigation for authentication check
        navView.setNavigationItemSelectedListener { menuItem ->
            if (menuItem.itemId == R.id.nav_puzzles && !DataRepository.getInstance().credentialsAvailable(this)) {
                Toast.makeText(this, R.string.puzzleOnlyForLoggedUsers, Toast.LENGTH_LONG).show()
                // User not logged in, redirect to login
                startActivity(Intent(this, LoginActivity::class.java))
                return@setNavigationItemSelectedListener false
            }
            navController.navigate(menuItem.itemId)
            drawerLayout.close()
            return@setNavigationItemSelectedListener true
        }

        val headerView = navView.getHeaderView(0)

        headerUsernameText = headerView.findViewById(R.id.headerUsername)
        headerRatingText = headerView.findViewById(R.id.headerRating)

        findViewById<View>(R.id.appbarLayout).addTopPadding()
    }

    override fun onResume() {
        refreshHeader()
        super.onResume()

        val loggedIn = DataRepository.getInstance().credentialsAvailable(this)
        navView.menu.findItem(R.id.nav_profile).isVisible = loggedIn
        navView.menu.findItem(R.id.nav_chat).isVisible = loggedIn
    }

    private fun refreshHeader(){
        if(DataRepository.getInstance().credentialsAvailable(this)){
            lifecycleScope.launch {
                try {
                    try{
                        login()
                    }catch (_: ForbiddenException){
                        // User session isn't valid anymore
                        Toast.makeText(this@MainActivity, R.string.loggedOut, Toast.LENGTH_LONG).show()
                        CredentialsStorage(this@MainActivity).clear()
                        return@launch
                    }
                    val user = getUserInfo()
                    headerUsernameText.text = user.username
                    headerRatingText.text = user.getRatingText()

                    // Fetch and save user role
                    fetchAndSaveRole()
                }catch (e: Exception){
                    e.printStackTrace()
                }
            }
        }
    }

    @WorkerThread
    private suspend fun login() = withContext(Dispatchers.IO) {
        DataRepository.getInstance().login(this@MainActivity, true)
    }

    @WorkerThread
    private suspend fun getUserInfo() = withContext(Dispatchers.IO) {
        DataRepository.getInstance().accessOpenAPI().findUser(CredentialsStorage(this@MainActivity).getUsername())
    }

    @WorkerThread
    private suspend fun fetchAndSaveRole() = withContext(Dispatchers.IO) {
        try {
            val roleResponse = DataRepository.getInstance().accessOpenAPI().getRole()
            CredentialsStorage(this@MainActivity).saveRole(roleResponse.role)
        } catch (e: Exception) {
            // If role fetching fails, keep default role
            e.printStackTrace()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}