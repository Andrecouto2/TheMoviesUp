package br.com.andrecouto.nextel.themoviesdbapp.ui.activity

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.view.MenuItemCompat
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.SearchView
import android.util.Log
import android.view.Menu
import android.view.View
import br.com.andrecouto.nextel.themoviesdbapp.App
import br.com.andrecouto.nextel.themoviesdbapp.R
import br.com.andrecouto.nextel.themoviesdbapp.adapter.MovieAdapter
import br.com.andrecouto.nextel.themoviesdbapp.data.component.DaggerMainScreenComponent
import br.com.andrecouto.nextel.themoviesdbapp.data.dao.DatabaseManager
import br.com.andrecouto.nextel.themoviesdbapp.data.model.CastResponse
import br.com.andrecouto.nextel.themoviesdbapp.data.model.Movie
import br.com.andrecouto.nextel.themoviesdbapp.data.model.MovieResponse
import br.com.andrecouto.nextel.themoviesdbapp.data.model.VideoResponse
import br.com.andrecouto.nextel.themoviesdbapp.data.module.MainScreenModule
import br.com.andrecouto.nextel.themoviesdbapp.ui.contract.MainScreenContract
import br.com.andrecouto.nextel.themoviesdbapp.ui.pagination.PaginationScrollListener
import br.com.andrecouto.nextel.themoviesdbapp.ui.presenter.MainScreenPresenter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.error_layout.*
import org.jetbrains.anko.custom.async
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.doAsyncResult
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.uiThread
import javax.inject.Inject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), MainScreenContract.View,
        MainScreenContract.PlayingNowMoviesView, MainScreenContract.DetailsMovieView,
        MainScreenContract.CastsMovieView, MainScreenContract.VideosMovieView {

    @Inject
    internal lateinit var mainPresenter: MainScreenPresenter
    internal lateinit var adapter: MovieAdapter
    internal lateinit var linearLayoutManager:LinearLayoutManager

    val pagination = 20
    var isLoadingInner = false
    var isLastPageInner = false
    var totalPages = 1
    var currentPage = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DaggerMainScreenComponent.builder()
                .netComponent((getApplicationContext() as App).netComponent)
                .mainScreenModule(MainScreenModule(this, this, this, this, this))
                .build().inject(this)
        linearLayoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        mainRecycler.setLayoutManager(linearLayoutManager)
        mainRecycler.setItemAnimator(DefaultItemAnimator())
        adapter = MovieAdapter(applicationContext, ArrayList()) { onClickMovie(it) }
        mainRecycler.adapter = adapter
        mainRecycler.addOnScrollListener(object: PaginationScrollListener(linearLayoutManager) {
            override val isLastPage: Boolean
                get() = isLastPageInner
            override val isLoading: Boolean
                get() = isLoadingInner
            override fun loadMoreItems() {
                if (!isLoadingInner) {
                    loadNextPage()
                    isLoadingInner = true
                    currentPage += 1
                }
            }
            override val totalPageCount: Int
                get() = totalPages
        })

        btnErrorRetry.setOnClickListener {
            hideErrorView();
            getMovies()
        }

       DatabaseManager.getMovieDAO().findAll()?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe { listOfMovies ->
                    totalPages = Math.round((listOfMovies.size/pagination).toDouble()).toInt()
                }

        supportActionBar.apply { title = "" }

        getMovies()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        getMenuInflater().inflate(R.menu.menu_movies, menu)
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = MenuItemCompat.getActionView(searchItem) as SearchView
        searchView.setSearchableInfo(searchManager.getSearchableInfo(ComponentName(this, SearchResultsActivity::class.java)))
        return true
    }

    override fun onSubscribe(d: Disposable) {
           //d.dispose()
    }

    fun getMovies() {
        val dao = DatabaseManager.getMovieDAO()
        dao.findAll()?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe { listOfMovies ->

                    if (listOfMovies.size >= currentPage * pagination) {
                        var start: Int = 0
                        if (currentPage > 1)
                            start = (currentPage - 1) * pagination

                        dao.findNext(start, pagination)?.subscribeOn(Schedulers.io())
                                ?.observeOn(AndroidSchedulers.mainThread())
                                ?.subscribe { listOfNextMovies ->
                                    addMovies(listOfNextMovies)
                                    isLoadingInner = false
                                }
                    } else {
                        mainPresenter.loadMovies(currentPage)
                    }

                }

    }

    open fun onClickMovie(movie: Movie) {
        if (movie.runtime!! > 0) {
            startActivity<DetailsMovieActivity>("movie" to movie)
        } else {
            mainPresenter.getCastsMovie(movie.id!!)
        }
    }

    private fun hideErrorView() {
        if (errorLayout.visibility === View.VISIBLE) {
            errorLayout.setVisibility(View.GONE)
            mainProgress.setVisibility(View.VISIBLE)
        }
    }

    override fun showError(message: String) {
        errorLayout.visibility = View.VISIBLE
        tErrorCause.setText(message)
        mainProgress.visibility = View.GONE
        isLoadingInner = false
        if (currentPage > 1) {
            currentPage -= 1
        }
    }

    override fun showComplete() {
        Log.e("complete", "completed")
        isLoadingInner = false
    }

    fun loadNextPage() {
        if (currentPage <= totalPages)
            adapter.addLoadingFooter()
        else
            isLastPageInner = true
        getMovies()
    }

    override fun showMovies(movies: MovieResponse?) {
        val dao = DatabaseManager.getMovieDAO()
        totalPages = movies!!.totalPages
        doAsync {
            dao.insert(movies!!.movieList)
            addMovies(movies!!.movieList)
        }
    }

    override fun showDetailsMovie(movie: Movie?) {
          doAsync {
              DatabaseManager.getMovieDAO().updateMovie(movie!!)
              startActivity<DetailsMovieActivity>("movie" to movie)
          }
    }

    override fun showCastsMovies(casts: CastResponse?) {
        doAsync {
            for (cast in casts!!.castList) {
                cast.movieId = casts!!.id
                DatabaseManager.getCastDAO().insert(cast)
            }
        }
        mainPresenter.getVideoMovie(casts!!.id)
    }

    override fun showVideosMovies(videos: VideoResponse?) {
        doAsync {
            for (video in videos!!.videoList) {
                video.movieId = videos!!.id
                DatabaseManager.getVideoDAO().insert(video)
            }
            mainPresenter.getDetailsMovie(videos!!.id)
        }
    }

    fun addMovies(movies: List<Movie>) {
        doAsync {
            uiThread {
                adapter.addAll(movies)
                adapter.removeLoadingFooter()
                mainProgress.visibility = View.GONE
            }
        }
    }
}
