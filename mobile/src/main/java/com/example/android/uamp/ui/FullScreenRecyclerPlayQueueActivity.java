/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.uamp.ui;

import android.Manifest;
import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.android.uamp.AlbumArtCache;
import com.example.android.uamp.MusicService;
import com.example.android.uamp.R;
import com.example.android.uamp.model.PlayQueueAdapter;
import com.example.android.uamp.model.PlayQueueRecyclerAdapter;
import com.example.android.uamp.model.recyclerhelpers.SimpleItemTouchHelperCallback;
import com.example.android.uamp.playback.PlaybackManager;
import com.example.android.uamp.settings.Settings;
import com.example.android.uamp.ui.MediaBrowserClient.MediaBrowserUampActivity;
import com.example.android.uamp.ui.dialogs.SetTimerDialog;
import com.example.android.uamp.utils.LogHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

/**
 * A full screen player that shows the current playing music with a list of the current play queue
 * The activity also has controls to seek/pause/play the audio.
 * The activity implements PlayQueueAdapter.PlayQueueActionsListener to receive callbacks
 * from playqueue item buttons (e.g. remove item from playqueue)
 *
 * This is supposed
 * to have the same functionality as FSplayQueue activity, but using a recycler
 * view for swipe to dismiss and drag to reorder
 */
public class FullScreenRecyclerPlayQueueActivity extends ActionBarCastActivity
        implements PlayQueueAdapter.PlayQueueActionsListener,
        SetTimerDialog.OnSleepTimerChangedListener,
        PlayQueueRecyclerAdapter.OnDragStartListener

{
    private static final String TAG = LogHelper.makeLogTag(FullScreenRecyclerPlayQueueActivity.class);
    private static final long PROGRESS_UPDATE_INTERNAL = 1000;
    private static final long PROGRESS_UPDATE_INITIAL_INTERVAL = 100;

    private ImageView mSkipPrev;
    private ImageView mSkipNext;
    private ImageView mPlayPause;
    private TextView mStart;
    private TextView mEnd;
    private SeekBar mSeekbar;
    private TextView mLine1;
    private TextView mLine2;
    private TextView mLine3;
    private ProgressBar mLoading;
    private View mControllers;
    private TextView mSleepIndicator;
    private Drawable mPauseDrawable;
    private Drawable mPlayDrawable;
    private RecyclerView mRecyclerPlayqueueList;
    private RecyclerView.LayoutManager mPlayQueueLayoutManager;
    private ActionBarCastActivity mActivity;

    private String mCurrentArtUrl;
    private final Handler mHandler = new Handler();
    private MediaBrowserCompat mMediaBrowser;

    private final Runnable mUpdateProgressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
        }
    };

    private final ScheduledExecutorService mExecutorService =
        Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> mScheduleFuture;
    private PlaybackStateCompat mLastPlaybackState;

    private PlayQueueRecyclerAdapter playQueueRecyclerAdapter;
    // For touching (swiping/re-ordering) on the recycler list
    // see: https://medium.com/@ipaulpro/drag-and-swipe-with-recyclerview-6a6f0c422efd
    private ItemTouchHelper mItemTouchHelper;

    private  MediaControllerCompat mediaController;

    private final MediaControllerCompat.Callback mCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            LogHelper.i(TAG, "onPlaybackstate changed", state);
            updatePlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata != null) {
                updateMediaDescription(metadata.getDescription());
                updateDuration(metadata);
            }
        }

        /**
         * I addded this.
         * Callback from the media session when the queue changes
         * @param queue
         */
        @Override
        public void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
            LogHelper.i(TAG, "onQueueChanger (mediaControllerCompat Callback");
            mDisplayedPlayQueue.clear(); // playQueueRecyclerAdapter.clear();
            for (MediaSessionCompat.QueueItem item : queue) {
                mDisplayedPlayQueue.add(item); // playQueueRecyclerAdapter.add(item);
            }
            playQueueRecyclerAdapter.notifyDataSetChanged();
        }
    };

    private final MediaBrowserCompat.ConnectionCallback mConnectionCallback =
            new MediaBrowserCompat.ConnectionCallback() {
        @Override
        public void onConnected() {
            LogHelper.i(TAG, "MediaBrowserCompat.ConnectionCallback: onConnected");
            try {
                connectToSession(mMediaBrowser.getSessionToken());
            } catch (RemoteException e) {
                LogHelper.e(TAG, e, "could not connect media controller");
            }
            LogHelper.i(TAG, "onConnected: Connected to media controller");
        }
    };

    // Override from PlayQueueRecyclerAdapter.OnDragStartListener
    // Called when drag starts on the list
    @Override
    public void onDragStarted(RecyclerView.ViewHolder viewHolder) {
        LogHelper.i(TAG, "OnDragStarted");
        mItemTouchHelper.startDrag(viewHolder);
    }

    @Override
    public void onItemRemoved(long uid) {
        LogHelper.i(TAG, "onItemRemoved: from=",uid);
        // this will cause the media session to call MediaSessionCallback.onRemoveQueueItem in PlaybackManager
        //mediaController.removeQueueItem(description);
        Bundle bundle = new Bundle();
        bundle.putLong(PlaybackManager.COMMAND_EXTRA_PARAMETER, uid);
        mediaController.sendCommand(PlaybackManager.COMMAND_REMOVE_FROM_PLAYQUEUE_BY_QUEUEID,bundle,null);
        //////////////////////////////Bundle bundle = new Bundle();
        //////////////////////////////bundle.putLong(BackgroundAudioService.UNIQUE_QUEUE_ID, uid);
        //////////////////////////////getMediaController().getTransportControls().sendCustomAction(BackgroundAudioService.COMMAND_REMOVE_SONG_FROM_QUEUE, bundle);

    }

    @Override
    public void onItemMoveComplete(int originalFromPosition, int finalToPosition) {
        LogHelper.i(TAG, "onItemMoveComplete: from=",originalFromPosition,", to=", finalToPosition);
        Bundle bundle = new Bundle();
        bundle.putInt(PlaybackManager.COMMAND_PARAMETER_POSITION_FROM, originalFromPosition);
        bundle.putInt(PlaybackManager.COMMAND_PARAMETER_POSITION_TO, finalToPosition);
        mediaController.sendCommand(PlaybackManager.COMMAND_REORDER_SONG_IN_QUEUE_BY_POSITION, bundle, null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        LogHelper.d(TAG, "onCreateOptionsMenu");
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.player_toolbar, menu);
        return true;
    }

    // handle user interaction with the menu
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        Intent fullScreenIntent;
        switch (item.getItemId()) {
            case R.id.action_show_now_playing:
                fullScreenIntent= new Intent(this, FullScreenRecyclerPlayQueueActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP |
                                Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(fullScreenIntent);

                return true;
            case R.id.action_show_choose:
                fullScreenIntent = new Intent(this, MediaBrowserUampActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP |
                                Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(fullScreenIntent);
                return true;
                /*
            case R.id.action_timer:
                showTimerDialog();
                return true;*/
        }

        return super.onOptionsItemSelected(item);
    }

    ArrayList<MediaSessionCompat.QueueItem> mDisplayedPlayQueue = new ArrayList<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        LogHelper.i(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        LogHelper.i(TAG, "check permissions");
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            LogHelper.i(TAG, "check permissions FAILED - start MainLauncher");
            // We don't have permission. So start the mainlauncher activity
            startActivity(new Intent(FullScreenRecyclerPlayQueueActivity.this, MainLauncherActivity.class));
            finish();
        }

        setContentView(R.layout.activity_full_recycler_playqueue);
        initializeToolbar();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mRecyclerPlayqueueList = (RecyclerView) findViewById(R.id.recyclerplayqueuelist);
        mPauseDrawable = ContextCompat.getDrawable(this, R.drawable.uamp_ic_pause_white_48dp);
        mPlayDrawable = ContextCompat.getDrawable(this, R.drawable.uamp_ic_play_arrow_white_48dp);
        mPlayPause = (ImageView) findViewById(R.id.play_pause);
        mSkipNext = (ImageView) findViewById(R.id.next);
        mSkipPrev = (ImageView) findViewById(R.id.prev);
        mStart = (TextView) findViewById(R.id.startText);
        mEnd = (TextView) findViewById(R.id.endText);
        mSeekbar = (SeekBar) findViewById(R.id.seekBar1);
        mLine1 = (TextView) findViewById(R.id.line1);
        mLine2 = (TextView) findViewById(R.id.line2);
        mLine3 = (TextView) findViewById(R.id.line3);
        mLoading = (ProgressBar) findViewById(R.id.progressBar1);
        mControllers = findViewById(R.id.controllers);

        mSleepIndicator = (TextView) findViewById(R.id.sleepIndicator);
        if (getMsTillSleep() == 0) {
            mSleepIndicator.setVisibility(View.INVISIBLE);
        } else {
            mSleepIndicator.setVisibility(View.VISIBLE);
        }

        // use a linear layout manager
        mPlayQueueLayoutManager = new LinearLayoutManager(this);
        mRecyclerPlayqueueList.setLayoutManager(mPlayQueueLayoutManager);

        playQueueRecyclerAdapter = new PlayQueueRecyclerAdapter(mDisplayedPlayQueue, this);
        mRecyclerPlayqueueList.setAdapter(playQueueRecyclerAdapter);

        // Create a callback that knows about our adapter
        ItemTouchHelper.Callback callback = new SimpleItemTouchHelperCallback(playQueueRecyclerAdapter);
        // Create and android ItemTouchHeler that knows about our callback
        mItemTouchHelper = new ItemTouchHelper(callback);
        // Android ItemTouchHelper method to attach the touch helper to our recycler view
        mItemTouchHelper.attachToRecyclerView(mRecyclerPlayqueueList);

        mActivity = this;

        mSkipNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogHelper.i(TAG, "mSkipNext onClickListener");
                MediaControllerCompat.TransportControls controls = MediaControllerCompat.getMediaController(mActivity).getTransportControls();
                controls.skipToNext();
            }
        });

        mSkipPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaControllerCompat.TransportControls controls = MediaControllerCompat.getMediaController(mActivity).getTransportControls();
                controls.skipToPrevious();
            }
        });

        mPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackStateCompat state = MediaControllerCompat.getMediaController(mActivity).getPlaybackState();
                if (state != null) {
                    MediaControllerCompat.TransportControls controls =
                            MediaControllerCompat.getMediaController(mActivity).getTransportControls();
                    switch (state.getState()) {
                        case PlaybackStateCompat.STATE_PLAYING: // fall through
                        case PlaybackStateCompat.STATE_BUFFERING:
                            controls.pause();
                            stopSeekbarUpdate();
                            break;
                        case PlaybackStateCompat.STATE_PAUSED:
                        case PlaybackStateCompat.STATE_STOPPED:
                            controls.play();
                            scheduleSeekbarUpdate();
                            break;
                        default:
                            LogHelper.i(TAG, "onClick with state ", state.getState());
                    }
                }
            }
        });

        mSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mStart.setText(DateUtils.formatElapsedTime(progress / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopSeekbarUpdate();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                MediaControllerCompat.getMediaController(mActivity).getTransportControls().seekTo(seekBar.getProgress());
                scheduleSeekbarUpdate();
            }
        });

        // Only update from the intent if we are not recreating from a config change:
        if (savedInstanceState == null) {
            updateFromParams(getIntent());
        }

        mMediaBrowser = new MediaBrowserCompat(this,
            new ComponentName(this, MusicService.class), mConnectionCallback, null);
    }

    private void connectToSession(MediaSessionCompat.Token token) throws RemoteException {
        LogHelper.i(TAG, "connectToSession");
        /* moved to class MediaControllerCompat */ mediaController = new MediaControllerCompat(
                FullScreenRecyclerPlayQueueActivity.this, token);

        // get the queue and show in the list
        List< MediaSessionCompat.QueueItem> queue = mediaController.getQueue();
        LogHelper.i(TAG, "connectToSession: queue == null?", queue==null);
        if (queue != null) {
            LogHelper.i(TAG, "connectToSession: queue size = ", queue.size());
            mDisplayedPlayQueue.clear();
            for (MediaSessionCompat.QueueItem item : queue) {
                mDisplayedPlayQueue.add(item);
            }
            playQueueRecyclerAdapter.notifyDataSetChanged();
        } else {
            LogHelper.i(TAG, "Queue is null");
        }

        MediaControllerCompat.setMediaController(this, mediaController);
        mediaController.registerCallback(mCallback);
        PlaybackStateCompat state = mediaController.getPlaybackState();
        updatePlaybackState(state);

        if (mediaController.getMetadata() == null) {
            // Here we don't have anything playing (no current item)
            // We enable the 'skip to next' control only, as this will start playing the first item on the queue
            LogHelper.i(TAG, "connectToSession, mediacontroller.getMetadata() is null");
            // Don't finish() just because nothing is playing. We still want to see the queue and the play controls.
            mPlayPause.setVisibility(INVISIBLE);
            mControllers.setVisibility(VISIBLE);
            mSkipNext.setVisibility(VISIBLE);
            mSeekbar.setVisibility(INVISIBLE);
            mSkipPrev.setVisibility(INVISIBLE);

            return;
        }

        MediaMetadataCompat metadata = mediaController.getMetadata();
        if (metadata != null) {
            updateMediaDescription(metadata.getDescription());
            updateDuration(metadata);
        } else {
            LogHelper.i(TAG, "Metadata is null");
        }
        updateProgress();
        if (state != null && (state.getState() == PlaybackStateCompat.STATE_PLAYING ||
                state.getState() == PlaybackStateCompat.STATE_BUFFERING)) {
            scheduleSeekbarUpdate();
        }
    }

    private void updateFromParams(Intent intent) {
        if (intent != null) {
            MediaDescriptionCompat description = intent.getParcelableExtra(
                MediaBrowserUampActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION);
            if (description != null) {
                updateMediaDescription(description);
            }
        }
    }

    private void scheduleSeekbarUpdate() {
        stopSeekbarUpdate();
        if (!mExecutorService.isShutdown()) {
            mScheduleFuture = mExecutorService.scheduleAtFixedRate(
                    new Runnable() {
                        @Override
                        public void run() {
                            mHandler.post(mUpdateProgressTask);
                        }
                    }, PROGRESS_UPDATE_INITIAL_INTERVAL,
                    PROGRESS_UPDATE_INTERNAL, TimeUnit.MILLISECONDS);
        }
    }

    private void stopSeekbarUpdate() {
        if (mScheduleFuture != null) {
            mScheduleFuture.cancel(false);
        }
    }

    @Override
    public void onStart() {
        LogHelper.i(TAG, "onStart");
        super.onStart();
        if (mMediaBrowser != null) {
            mMediaBrowser.connect();
        }
    }

    @Override
    public void onStop() {
        LogHelper.i(TAG, "onStop");
        super.onStop();
        if (mMediaBrowser != null) {
            mMediaBrowser.disconnect();
        }
        if (MediaControllerCompat.getMediaController(mActivity) != null) {
            MediaControllerCompat.getMediaController(mActivity).unregisterCallback(mCallback);
        }
    }

    @Override
    public void onDestroy() {
        LogHelper.i(TAG, "onDestroy");
        super.onDestroy();
        stopSeekbarUpdate();
        mExecutorService.shutdown();
    }

    private void fetchImageAsync(@NonNull MediaDescriptionCompat description) {
        if (description.getIconUri() == null) {
            return;
        }
        String artUrl = description.getIconUri().toString();
        mCurrentArtUrl = artUrl;
        AlbumArtCache cache = AlbumArtCache.getInstance();
        Bitmap art = cache.getBigImage(artUrl);
        if (art == null) {
            art = description.getIconBitmap();
        }
        if (art != null) {
            // if we have the art cached or from the MediaDescription, use it:
//            mBackgroundImage.setImageBitmap(art);
        } else {
            // otherwise, fetch a high res version and update:
            cache.fetch(artUrl, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    // sanity check, in case a new fetch request has been done while
                    // the previous hasn't yet returned:
                    if (artUrl.equals(mCurrentArtUrl)) {
//                        mBackgroundImage.setImageBitmap(bitmap);
                    }
                }
            });
        }
    }

    private void updateMediaDescription(MediaDescriptionCompat description) {
        if (description == null) {
            return;
        }
        LogHelper.i(TAG, "updateMediaDescription called ");
        mLine1.setText(description.getTitle());
        mLine2.setText(description.getSubtitle());
//        fetchImageAsync(description);
    }

    private void updateDuration(MediaMetadataCompat metadata) {
        if (metadata == null) {
            return;
        }
        LogHelper.i(TAG, "updateDuration called ");
        int duration = (int) metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        mSeekbar.setMax(duration);
        mEnd.setText(DateUtils.formatElapsedTime(duration/1000));
    }

    private void updatePlaybackState(PlaybackStateCompat state) {
        LogHelper.i(TAG, "updatePlaybackState called, state =  ", state);
        if (state == null) {
            return;
        }
        mLastPlaybackState = state;
        if (MediaControllerCompat.getMediaController(mActivity) != null && MediaControllerCompat.getMediaController(mActivity).getExtras() != null) {
            String castName = MediaControllerCompat.getMediaController(mActivity)
                    .getExtras().getString(MusicService.EXTRA_CONNECTED_CAST);
            String line3Text = castName == null ? "" : getResources()
                        .getString(R.string.casting_to_device, castName);
            mLine3.setText(line3Text);
        }
        mSeekbar.setVisibility(VISIBLE);
        switch (state.getState()) {
            case PlaybackStateCompat.STATE_PLAYING:
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPauseDrawable);
                mControllers.setVisibility(VISIBLE);
                scheduleSeekbarUpdate();
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                mControllers.setVisibility(VISIBLE);
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekbarUpdate();
                break;
            case PlaybackStateCompat.STATE_NONE:
                LogHelper.i(TAG, "STATE_NONE");
                mControllers.setVisibility(VISIBLE);
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekbarUpdate();
                break;
            case PlaybackStateCompat.STATE_STOPPED:
                LogHelper.i(TAG, "STATE_STOPPED");
                mControllers.setVisibility(VISIBLE); // CHANGED
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekbarUpdate();
                break;
            case PlaybackStateCompat.STATE_BUFFERING:
                mPlayPause.setVisibility(INVISIBLE);
                mLoading.setVisibility(VISIBLE);
                mLine3.setText(R.string.loading);
                stopSeekbarUpdate();
                break;
            default:
                LogHelper.i(TAG, "Unhandled state ", state.getState());
        }

        mSkipNext.setVisibility((state.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) == 0
            ? INVISIBLE : VISIBLE );
        mSkipPrev.setVisibility((state.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) == 0
            ? INVISIBLE : VISIBLE );
    }

    private void updateProgress() {
        // LogHelper.i(TAG, "updateProgress");
        if (mLastPlaybackState == null) {
            return;
        }
        long currentPosition = mLastPlaybackState.getPosition();
        if (mLastPlaybackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            // Calculate the elapsed time between the last position update and now and unless
            // paused, we can assume (delta * speed) + current position is approximately the
            // latest position. This ensure that we do not repeatedly call the getPlaybackState()
            // on MediaControllerCompat.
            long timeDelta = SystemClock.elapsedRealtime() -
                    mLastPlaybackState.getLastPositionUpdateTime();
            currentPosition += (int) timeDelta * mLastPlaybackState.getPlaybackSpeed();
        }
        mSeekbar.setProgress((int) currentPosition);
    }

    /**
     * Implements method from PlayQueueAdapter.PlayQueueActionsListener
     * Called by the PlayQueueAdapter when remove button is clicked on an item
     * @param queueId
     * @param description
     */
    @Override
    public void onRemoveSongClicked(long queueId, MediaDescriptionCompat description) {
        LogHelper.i(TAG, "onRemoveSongClicked ",  queueId, "description =", description);
        // this will cause the media session to call MediaSessionCallback.onRemoveQueueItem in PlaybackManager
        //mediaController.removeQueueItem(description);
        Bundle bundle = new Bundle();
        bundle.putLong(PlaybackManager.COMMAND_EXTRA_PARAMETER, queueId);
        mediaController.sendCommand(PlaybackManager.COMMAND_REMOVE_FROM_PLAYQUEUE_BY_QUEUEID,bundle,null);
    }

    /**
     * Implements method from PlayQueueAdapter.PlayQueueActionsListener
     * Called by the PlayQueueAdapter when move to top button is clicked on an item
     * @param queueId

     */
    @Override
    public void onMoveSongToTopClicked(long queueId) {
        LogHelper.i(TAG, "onMoveSongToTopClicked ",  queueId);
        // this will cause the media session to call MediaSessionCallback.onMoveQueueItemToTop in PlaybackManager
        Bundle bundle = new Bundle();
        bundle.putLong(PlaybackManager.COMMAND_EXTRA_PARAMETER, queueId);
        mediaController.sendCommand(PlaybackManager.COMMAND_PLAYQUEUE_MOVE_TO_TOP_BY_QUEUEID,bundle,null);
    }

    @Override
    public void handleExtraDrawerItems(int itemToOpenWhenDrawerCloses) {
        LogHelper.i(TAG, "handleExtraDrawerItems ");
        switch (itemToOpenWhenDrawerCloses) {
            case R.id.navigation_sleep:
                showTimerDialog();
                break;
        }
    }

    @Override
    public void handleDrawerOpening() {
        super.handleDrawerOpening();

        LogHelper.i(TAG, "opening");
        long timeToGoToSleep = Settings.getTimeToGoToSleep(this);
        String title;

        if (timeToGoToSleep == 0) {
            title = "Set sleep timer";
        } else {
            title = "Cancel sleep timer";
        }

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.navigation_sleep).setTitle(title);

    }

    /**
     * Returns the number of ms until we should sleep
     * @return 0 if no sleep timer set
     */
    private long getMsTillSleep() {
        long timeToGoToSleep = Settings.getTimeToGoToSleep(this);
        if (timeToGoToSleep == 0) {
            return 0;
        }
        long currentTimeInMS = System.currentTimeMillis();
        long msTillSleep = timeToGoToSleep - currentTimeInMS;
        return msTillSleep;
    }

    // For the sleep timer dialog
    public void showTimerDialog() {
        LogHelper.i(TAG, "showTimerDialog: ");
        FragmentManager fm = getFragmentManager();

        long msTillSleep = getMsTillSleep();

        if (msTillSleep == 0) {
            SetTimerDialog setSleepTimerDialog = new SetTimerDialog();
            setSleepTimerDialog.setOnSetSleepTimerListener(this);
            setSleepTimerDialog.show(fm, "fragment_settimer_dialog");
        } else {
            // sleep timer is active ... allow user to cancel
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            String msg = getSleepTimeInWords();
            /*
            if (msTillSleep < 0) {
                msg = "Sleep at end of playing song";
            } else if (msTillSleep < 60 * 1000) {
                msg = "Sleep in less than one minute";
            } else {
                msg = "Sleep in " + Long.toString(msTillSleep/1000) + " seconds ";
            }
*/
            builder.setTitle("Cancel sleep timer")
                    .setMessage(msg + "\nCancel?")
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Do nothing
                            dialog.dismiss();
                        }
                    })
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            LogHelper.i(TAG, "Positive button onClick: ");

                            Settings.setTimeToGoToSleep(FullScreenRecyclerPlayQueueActivity.this , 0);
                            mSleepIndicator.setVisibility(View.INVISIBLE);
                            dialog.dismiss();
                        }
                    });

            AlertDialog alert = builder.create();
            alert.show();
        }
    }

    private String getSleepTimeInWords() {
        long msTillSleep = getMsTillSleep();
        long secsTillSleep = (msTillSleep - (msTillSleep % 1000)) / 1000;

        if (secsTillSleep < 60) {
            return (Long.toString(secsTillSleep) + " Seconds until sleep");
        }

        long seconds = secsTillSleep % 60;
        long minutes = (secsTillSleep - seconds) / 60;
        if (secsTillSleep < 3600) {
            return (Long.toString(minutes) + " minutes, " + seconds + " Seconds until sleep");
        }

        long hours = (secsTillSleep - (secsTillSleep % 3600)) /3600;
        minutes = minutes - 60 * hours;
        return (Long.toString(hours) + " hours, " +Long.toString(minutes) + " minutes, " + seconds + " Seconds until sleep");
    }

    @Override
    public void onSleepTimerChanged(int minsTillSleep) {
        LogHelper.i(TAG, "onSleepTimerChanged: ", minsTillSleep);

        long currentTimeinMS = System.currentTimeMillis();
        long timeToGoToSleep = currentTimeinMS + minsTillSleep * 60 * 1000;
        Settings.setTimeToGoToSleep(this , timeToGoToSleep);
        mSleepIndicator.setVisibility(View.VISIBLE);
    }
}
