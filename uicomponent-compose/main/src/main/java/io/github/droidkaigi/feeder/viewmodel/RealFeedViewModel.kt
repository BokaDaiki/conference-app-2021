package io.github.droidkaigi.feeder.viewmodel

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.droidkaigi.feeder.FeedContents
import io.github.droidkaigi.feeder.Filters
import io.github.droidkaigi.feeder.LoadState
import io.github.droidkaigi.feeder.PlayingPodcastState
import io.github.droidkaigi.feeder.core.util.ProgressTimeLatch
import io.github.droidkaigi.feeder.feed.FeedViewModel
import io.github.droidkaigi.feeder.getContents
import io.github.droidkaigi.feeder.orEmptyContents
import io.github.droidkaigi.feeder.repository.FeedRepository
import io.github.droidkaigi.feeder.toLoadState
import javax.annotation.meta.Exhaustive
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class RealFeedViewModel @Inject constructor(
    private val repository: FeedRepository,
) : ViewModel(), FeedViewModel {

    private val effectChannel = Channel<FeedViewModel.Effect>(Channel.UNLIMITED)
    private val showProgressLatch = ProgressTimeLatch(viewModelScope = viewModelScope)
    override val effect: Flow<FeedViewModel.Effect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    private val mediaPlayer by lazy {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
            )
        }
    }

    private val allFeedContents: StateFlow<LoadState<FeedContents>> = repository.feedContents()
        .toLoadState()
        .onEach { loadState ->
            if (loadState.isError()) {
                // FIXME: smartcast is not working
                val error = loadState as LoadState.Error
                error.getThrowableOrNull()?.printStackTrace()
                effectChannel.send(FeedViewModel.Effect.ErrorMessage(error.e))
            }
            showProgressLatch.refresh(loadState.isLoading())
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, LoadState.Loading)
    private val filters: MutableStateFlow<Filters> = MutableStateFlow(Filters())
    private val playingPodcastState = MutableStateFlow<PlayingPodcastState?>(null)

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun playPodcast(
        url: String,
        isRestart: Boolean,
    ) = withContext(Dispatchers.IO) {
        with(mediaPlayer) {
            if (isRestart) return@with start()
            reset()
            setDataSource(url)
            prepare()
            start()
        }
    }

    private fun pausePodcast() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause()
    }

    override val state: StateFlow<FeedViewModel.State> =
        combine(
            allFeedContents,
            filters,
            playingPodcastState,
            showProgressLatch.toggleState
        ) { feedContentsLoadState, filters, playingPodcastState, showProgress ->
            val filteredFeed =
                feedContentsLoadState.getValueOrNull().orEmptyContents().filtered(filters)
            FeedViewModel.State(
                showProgress = showProgress,
                filters = filters,
                playingPodcastState = playingPodcastState,
                filteredFeedContents = filteredFeed,
//                snackbarMessage = currentValue.snackbarMessage
            )
        }
            .stateIn(
                scope = viewModelScope,
                // prefetch when splash screen
                started = SharingStarted.Eagerly,
                initialValue = FeedViewModel.State()
            )

    override fun event(event: FeedViewModel.Event) {
        viewModelScope.launch {
            @Exhaustive
            when (event) {
                is FeedViewModel.Event.ChangeFavoriteFilter -> {
                    filters.value = event.filters
                }
                is FeedViewModel.Event.ToggleFavorite -> {
                    val favorite = allFeedContents.value
                        .getContents()
                        .favorites
                        .contains(event.feedItem.id)
                    if (favorite) {
                        repository.removeFavorite(event.feedItem)
                    } else {
                        repository.addFavorite(event.feedItem)
                    }
                }
                is FeedViewModel.Event.ChangePlayingPodcastState -> {
                    val state = playingPodcastState.value
                    val isPlaying = event.feedItem.id == state?.id && state.isPlaying

                    val newState = PlayingPodcastState(
                        id = event.feedItem.id,
                        url = event.feedItem.podcastLink(),
                        isPlaying = isPlaying.not()
                    )
                    if (newState.isPlaying) {
                        playPodcast(newState.url, state?.url == newState.url)
                    } else {
                        pausePodcast()
                    }
                    playingPodcastState.value = newState
                }
                is FeedViewModel.Event.ReloadContent -> {
                    repository.refresh()
                }
            }
        }
    }
}
