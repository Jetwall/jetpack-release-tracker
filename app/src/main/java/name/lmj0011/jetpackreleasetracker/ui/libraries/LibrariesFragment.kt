package name.lmj0011.jetpackreleasetracker.ui.libraries

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import name.lmj0011.jetpackreleasetracker.MainActivity
import name.lmj0011.jetpackreleasetracker.R
import name.lmj0011.jetpackreleasetracker.database.AppDatabase
import name.lmj0011.jetpackreleasetracker.databinding.FragmentLibrariesBinding
import name.lmj0011.jetpackreleasetracker.helpers.AndroidXLibraryDataset
import name.lmj0011.jetpackreleasetracker.helpers.adapters.AndroidXLibraryListAdapter
import name.lmj0011.jetpackreleasetracker.helpers.factories.LibrariesViewModelFactory
import name.lmj0011.jetpackreleasetracker.helpers.interfaces.SearchableRecyclerView
import name.lmj0011.jetpackreleasetracker.helpers.workers.LibraryRefreshWorker
import name.lmj0011.jetpackreleasetracker.helpers.workers.ProjectSyncAllWorker
import timber.log.Timber

class LibrariesFragment : Fragment(),
    SearchableRecyclerView
{

    private lateinit var binding: FragmentLibrariesBinding
    private lateinit var mainActivity: MainActivity
    private lateinit var librariesViewModel: LibrariesViewModel
    private lateinit var listAdapter: AndroidXLibraryListAdapter
    private lateinit var filterMenuItem: MenuItem
    private var fragmentJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main +  fragmentJob)

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_libraries, container, false)
        mainActivity = activity as MainActivity
        setHasOptionsMenu(true)


        val application = requireNotNull(this.activity).application
        val dataSource = AppDatabase.getInstance(application).androidXArtifactDao
        val viewModelFactory = LibrariesViewModelFactory(dataSource, application)
        librariesViewModel = ViewModelProvider(this, viewModelFactory).get(LibrariesViewModel::class.java)

        listAdapter = AndroidXLibraryListAdapter(
            AndroidXLibraryListAdapter.AndroidXLibraryListener {},
            AndroidXLibraryListAdapter.AndroidXLibraryStarListener {
                val spf = PreferenceManager.getDefaultSharedPreferences(mainActivity)
                // make a copy since original value can't reliably be modified; ref: https://stackoverflow.com/a/51001329/2445763
                val starredSet = (spf.getStringSet(this.getString(R.string.pref_key_starred_libraries), mutableSetOf<String>()) as MutableSet<String>).toMutableSet()

                if(starredSet.contains(it.packageName)){
                    starredSet.remove(it.packageName)
                } else {
                    starredSet.add(it.packageName)
                }

                spf.edit{
                    putStringSet(mainActivity.getString(R.string.pref_key_starred_libraries), starredSet)
                    apply()
                    this@LibrariesFragment.refreshListAdapter()
                }
            }
        )

        binding.androidXLibraryList.addItemDecoration(DividerItemDecoration(mainActivity, DividerItemDecoration.VERTICAL))
        binding.androidXLibraryList.adapter = listAdapter
        binding.homeViewModel = librariesViewModel
        binding.lifecycleOwner = this


        librariesViewModel.artifacts.observe(viewLifecycleOwner, Observer {

            // if it's always empty then there's a problem with the network or library source
            if (it.isEmpty()) {
                enqueueNewLibraryRefreshWorkerRequest()
            }

            listAdapter.submitLibArtifacts(it.toList())
            refreshListAdapter()
        })

        binding.librariesSearchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                this@LibrariesFragment.refreshListAdapter(newText)
                return false
            }
        })

        binding.librariesSearchView.setOnCloseListener {
            this@LibrariesFragment.toggleSearch(mainActivity, binding.librariesSearchView, false)
            false
        }

        binding.librariesSearchView.setOnQueryTextFocusChangeListener { view, hasFocus ->
            if (hasFocus) { } else{
                binding.librariesSearchView.setQuery("", true)
                this@LibrariesFragment.toggleSearch(mainActivity, binding.librariesSearchView, false)
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            enqueueNewLibraryRefreshWorkerRequest()
            binding.swipeRefresh.isRefreshing = false
            mainActivity.showToastMessage(mainActivity.getString(R.string.toast_message_updating_libraries))
        }


        if(!resources.getBoolean(R.bool.DEBUG_MODE)) {
            binding.testButton.visibility = View.GONE
        }


        mainActivity.hideFab()

        return binding.root
    }

    override fun onDestroy() {
        super.onDestroy()
        fragmentJob?.cancel()
    }

    private fun enqueueNewLibraryRefreshWorkerRequest() {
        val libraryRefreshWorkerRequest = OneTimeWorkRequestBuilder<LibraryRefreshWorker>()
            .addTag(mainActivity.getString(R.string.update_one_time_worker_tag))
            .build()

        WorkManager.getInstance(mainActivity)
            .getWorkInfoByIdLiveData(libraryRefreshWorkerRequest.id)
            .observe(viewLifecycleOwner, Observer { workInfo ->
                if (workInfo != null) {
                    val progress = workInfo.progress
                    val value = progress.getInt(ProjectSyncAllWorker.Progress, 0)

                    if (value >= 100) {
                        librariesViewModel.refreshLibraries()
                    }
                }
            })

        WorkManager.getInstance(mainActivity).enqueue(libraryRefreshWorkerRequest)
    }

    private fun refreshListAdapter (query: String? = null) {
        val spf = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val hasStarredFilter = spf.getBoolean(mainActivity.getString(R.string.pref_key_starred_filter), false)
        var list = AndroidXLibraryDataset.data.toMutableList()

        uiScope.launch {
            if (hasStarredFilter) {
                list = listAdapter.filterByStarred(mainActivity, list).toMutableList()
            }

            query?.let { str ->
               list = withContext(Dispatchers.Default) {
                    listAdapter.filterBySearchQuery(str, list)
                }
            }

            listAdapter.submitList(list)
            listAdapter.notifyDataSetChanged()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.libraries_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        val spf = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        filterMenuItem = menu.findItem(R.id.action_libraries_filter)
        val hasStarredFilter = spf.getBoolean(mainActivity.getString(R.string.pref_key_starred_filter), false)

        if (hasStarredFilter) {
            filterMenuItem.setIcon(R.drawable.ic_baseline_selected_filter_list_24)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_libraries_search -> {
                this@LibrariesFragment.toggleSearch(mainActivity, binding.librariesSearchView, true)
                true
            }
            R.id.action_libraries_filter -> {
                val spf = PreferenceManager.getDefaultSharedPreferences(mainActivity)
                val hasStarredFilter = spf.getBoolean(mainActivity.getString(R.string.pref_key_starred_filter), false)
                var checkedItem = -1
                if (hasStarredFilter) checkedItem = 0

                MaterialAlertDialogBuilder(mainActivity)
                    .setTitle("Filter By")
                    .setSingleChoiceItems(arrayOf("Starred"), checkedItem) { dialog, which ->
                        // Respond to item chosen
                        if (hasStarredFilter) {
                            spf.edit().putBoolean(mainActivity.getString(R.string.pref_key_starred_filter), false).commit()
                            filterMenuItem.setIcon(R.drawable.ic_baseline_filter_list_24)
                        } else {
                            spf.edit().putBoolean(mainActivity.getString(R.string.pref_key_starred_filter), true).commit()
                            filterMenuItem.setIcon(R.drawable.ic_baseline_selected_filter_list_24)
                        }
                        this@LibrariesFragment.refreshListAdapter()
                        dialog.dismiss()
                    }
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
