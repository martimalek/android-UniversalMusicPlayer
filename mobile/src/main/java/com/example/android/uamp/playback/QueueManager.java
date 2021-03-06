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

package com.example.android.uamp.playback;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import com.example.android.uamp.AlbumArtCache;
import com.example.android.uamp.R;
import com.example.android.uamp.database.QueuedSong;
import com.example.android.uamp.database.QueuedSongRepository;
import com.example.android.uamp.model.MusicProvider;
import com.example.android.uamp.settings.Settings;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;
import com.example.android.uamp.utils.QueueHelper;

import java.util.*;

/**
 * Simple data provider for queues. Keeps track of a current queue and a current index in the
 * queue. Also provides methods to set the current queue based on common queries, relying on a
 * given MusicProvider to provide the actual media metadata.
 */
public class QueueManager {
    private static final String TAG = LogHelper.makeLogTag(QueueManager.class);

    private MusicProvider mMusicProvider;
    private MetadataUpdateListener mListener;
    private Resources mResources;
    private Context mContext;

    private QueuedSongRepository mQueuedSongRepository;

    // "Now playing" queue:
    private List<MediaSessionCompat.QueueItem> mPlayingQueue;

    // get rid of the current index and add a now playing track
    // In our implementation the track being played isn't part of the queue
    // The queue only represents tracks waiting to be played.

    // private int mCurrentIndex;
    private MediaSessionCompat.QueueItem mNowPlaying;



    public QueueManager(@NonNull MusicProvider musicProvider,
                        @NonNull Resources resources,
                        @NonNull Context context,
                        @NonNull MetadataUpdateListener listener) {
        this.mMusicProvider = musicProvider;
        this.mListener = listener;
        this.mResources = resources;
        this.mContext = context;

        mPlayingQueue = Collections.synchronizedList(new ArrayList<MediaSessionCompat.QueueItem>());
        mQueuedSongRepository = new QueuedSongRepository(context);
        // the current index is replaced by now playing in this implementation
        // mCurrentIndex = 0;
        mNowPlaying = null;
    }

    public boolean isSameBrowsingCategory(@NonNull String mediaId) {
        String[] newBrowseHierarchy = MediaIDHelper.getHierarchy(mediaId);
        MediaSessionCompat.QueueItem current = getCurrentMusic();
        if (current == null) {
            return false;
        }
        String[] currentBrowseHierarchy = MediaIDHelper.getHierarchy(
                current.getDescription().getMediaId());

        return Arrays.equals(newBrowseHierarchy, currentBrowseHierarchy);
    }

    // I added this ...
    private List<MediaSessionCompat.QueueItem> getCurrentQueue() {
        return mPlayingQueue;
    }

    // This isn't really used in our implementation
    // In our implementation we take the item out of the queue and we set it now playing
    // example implementation just set the current index
    private void setCurrentQueueIndex(int index) {
        if (index >= 0 && index < mPlayingQueue.size()) {
            mNowPlaying = mPlayingQueue.remove(index);
            // mCurrentIndex = index;

            // I've replaced onCurrentQueueIndexChanged with the following:
            mListener.onNowPlayingChanged(mNowPlaying);
            // the following call doesn't make sense anymore
            // mListener.onCurrentQueueIndexUpdated(mCurrentIndex);
        }
    }

    // In our implementation we take the item out of the queue and we set it now playing
    // example implementation just set the current index
    // No change to the code as we are using the refactored setCurrentQueueIndex()
    public boolean setCurrentQueueItem(long queueId) {
        // set the current index on queue from the queue Id:
        int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, queueId);
        setCurrentQueueIndex(index);
        return index >= 0;
    }

    // In our implementation we take the item out of the queue and we set it now playing
    // example implementation just set the current index
    // No change to the code as we are using the refactored setCurrentQueueIndex()
    public boolean setCurrentQueueItem(String mediaId) {
        // set the current index on queue from the music Id:
        int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, mediaId);
        setCurrentQueueIndex(index);
        return index >= 0;
    }

    /**
     * Our implementation will use this instead of skipQueuePosition
     * As our only control is 'go to next track'. No need to skip 'n' tracks
     * @return
     */
    public boolean goToNextSong() {
        LogHelper.i(TAG, "goToNextSong queue size=", mPlayingQueue.size());
        if (mPlayingQueue.size() > 0) {
            // get the next track as the first in the queue and set it to now playing
            mNowPlaying = mPlayingQueue.remove(0);

            // TEMP
            // Add another item into the queue
            // In fact fill the queue up to n places, in case the queue size is < n (queued items were removed by user, maybe)
            // Don't add items if queus alredy has >n items (items were added manually by the user)
            fillRandomQueue();
            return true;
        }
        return false;
    }

    /**
     * Skips [amount] songs through the queue
     * Amount = -1 means skip to previous track
     * @param amount
     * @return true if the new index is a valid playable media item
     */
    // We should only allow skip to next...
    // But for the moment leave it like this.
    public boolean skipQueuePosition(int amount) {
        LogHelper.i(TAG, "skip queue by ", Integer.toString(amount), "queue size=", mPlayingQueue.size());
        int index = /* mCurrentIndex + */ amount; // in principle the current index is always 0 in our implementation.
        if (index < 0) {
            // skip backwards before the first song will keep you on the first song
            index = 0;
        } else if (index >= mPlayingQueue.size()) {
            // in the example skip forwards when in last song will cycle back to start of the queue
            // index %= mPlayingQueue.size();
            // in our example it returns false
            return false;
        }
        if (!QueueHelper.isIndexPlayable(index, mPlayingQueue)) {
            LogHelper.e(TAG, "Cannot increment queue index by ", amount,
                    " queue length=", mPlayingQueue.size());
            return false;
        }
        // strange that in the example we just update the index.
        // there is no call(back) to any listener
        // so we just do the same (remove from queue and update now playing)
        mNowPlaying = mPlayingQueue.remove(index);
        // mCurrentIndex = index;
        return true;
    }

    public boolean setQueueFromSearch(String query, Bundle extras) {
        LogHelper.i(TAG, "SetQueuefromSearch: query = ", query);
        List<MediaSessionCompat.QueueItem> queue =
                QueueHelper.getPlayingQueueFromSearch(query, extras, mMusicProvider);
        String title =  mResources.getString(R.string.search_queue_title);
        setCurrentQueue(title, queue);
        mListener.onQueueUpdated(title, mPlayingQueue);
        updateMetadata();
        return queue != null && !queue.isEmpty();
    }

    // Fills the queue up to n items by adding Random tracks.
    // If the queue is already >= n then there is no need to do anything
    public void fillRandomQueue() {
        int currentQueueSize = mPlayingQueue.size();
        LogHelper.i(TAG, "fillRandomQueue, current size = ", mPlayingQueue.size());
        if (currentQueueSize < Settings.getPlayQueueSize(mContext))
        {
            List<MediaSessionCompat.QueueItem> newTracks =  QueueHelper.getRandomQueue(mMusicProvider, Settings.getPlayQueueSize(mContext) - currentQueueSize);

            // Add the new songs (we do this in loop below)
            // mPlayingQueue.addAll(newTracks);

            LogHelper.i(TAG, "ADDING ", newTracks.size(), " NEW SONGS TO DB");
            for (MediaSessionCompat.QueueItem item: newTracks) {
                mPlayingQueue.add(item);
                int order = mPlayingQueue.size();
                QueuedSong qs= new QueuedSong(0, order, 0, item.getDescription().getMediaId());
                LogHelper.i(TAG, "ADDING SONG TO DB, order = ", order, " desc= ", item.getDescription().getMediaId());
                mQueuedSongRepository.insertQueuedSong(qs);
            }

        }

        mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
    }

    public void setQueueFromMusic(String mediaId) {
        LogHelper.d(TAG, "setQueueFromMusic", mediaId);

        // The mediaId used here is not the unique musicId. This one comes from the
        // MediaBrowser, and is actually a "hierarchy-aware mediaID": a concatenation of
        // the hierarchy in MediaBrowser and the actual unique musicID. This is necessary
        // so we can build the correct playing queue, based on where the track was
        // selected from.
        boolean canReuseQueue = false;
        if (isSameBrowsingCategory(mediaId)) {
            canReuseQueue = setCurrentQueueItem(mediaId);
        }
        if (!canReuseQueue) {
            String queueTitle = mResources.getString(R.string.browse_musics_by_genre_subtitle,
                    MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId));
            setCurrentQueue(queueTitle,
                    QueueHelper.getPlayingQueue(mediaId, mMusicProvider), mediaId);
        }
        updateMetadata();
    }

    /**
     * Added by me to allow removal of tracks from the playqueue
     * Relies on the matching media id in the description
     * Will remove ALL tracks from the queue which have matching media IDs
     * i.e. clicking 'Blue Suede Shoes' removes all instances of blue suede shoes
     * - Actually not quite. If one was added by artist, and another by album (or randomly chosen)
     * - then only the complete match would be removed, so this could be confusing behaviour
     * Could be improved to use some unique queue id. Or the media URI
     * Here we have to use part of the MediaDescriptionCompat because we are using the standard callback
     * MediaSessionCallback.onRemoveQueueItem(MediaDescriptionCompat description)
     * (The standard implementation replaces the queue)
     * @param description
     */
    public void removeQueueItemByDescription(MediaDescriptionCompat description) {
        String mediaID = description.getMediaId();
        String itemMediaID;
        LogHelper.i(TAG, "removeQueueItemByDescription ", mediaID);
        Iterator<MediaSessionCompat.QueueItem> it = mPlayingQueue.iterator();
        // safe removal from list (don't use for)
        boolean hasChanged = false;
        while (it.hasNext()) {
            MediaSessionCompat.QueueItem item = it.next();
            MediaDescriptionCompat itemDescription = item.getDescription();
            itemMediaID = itemDescription.getMediaId();
            LogHelper.i(TAG, "itemMediaID", itemMediaID);
            if (itemMediaID.equals(mediaID)) {
                LogHelper.i(TAG, "found item");
                hasChanged = true;
                it.remove();
            }
        }
        if (hasChanged) {
            // if the new queue has less than N items then fill it randomly
            fillRandomQueue();
            mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
        }
    }

    /**
     * Implemented for reordering using recycler view
     * @param originalFromPosition
     * @param finalToPosition
     */
    public void reorderQueuebyPositions(int originalFromPosition, int finalToPosition) {
        LogHelper.i(TAG, "reorderQueuebyPositions o=",originalFromPosition, " f=", finalToPosition);
        mPlayingQueue.add(finalToPosition, mPlayingQueue.remove(originalFromPosition));
        mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
    }

    public void moveQueueItemToTopByQueueId(long queueId) {
        LogHelper.i(TAG, "moveQueueItemToTopByQueueId ", queueId);
        Iterator<MediaSessionCompat.QueueItem> it = mPlayingQueue.iterator();
        MediaSessionCompat.QueueItem item = null;
        // safe removal from list (don't use for)
        boolean hasChanged = false;
        long itemQueueId;
        while (hasChanged == false && it.hasNext()) {
            item = it.next();
            MediaDescriptionCompat itemDescription = item.getDescription();
            itemQueueId = item.getQueueId();
            LogHelper.i(TAG, "itemQueueId", itemQueueId);
            if (itemQueueId == queueId) {
                LogHelper.i(TAG, "found item");
                hasChanged = true;
                it.remove();
            }
        }
        if (hasChanged) {
            mPlayingQueue.add(0, item);

            // if the new queue has less than N items then fill it randomly
            fillRandomQueue();
            mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
        }
    }

    public void removeQueueItemByQueueId(long queueId) {
        Iterator<MediaSessionCompat.QueueItem> it = mPlayingQueue.iterator();
        // safe removal from list (don't use for)
        boolean hasChanged = false;
        long itemQueueId;
        while (hasChanged == false && it.hasNext()) {
            MediaSessionCompat.QueueItem item = it.next();
            MediaDescriptionCompat itemDescription = item.getDescription();
            itemQueueId = item.getQueueId();
            LogHelper.i(TAG, "itemQueueId", itemQueueId);
            if (itemQueueId == queueId) {
                LogHelper.i(TAG, "found item");
                hasChanged = true;
                it.remove();
            }
        }
        if (hasChanged) {
            // if the new queue has less than N items then fill it randomly
            fillRandomQueue();
            mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
        }
    }
    /**
     * Add all tracks from a specified album to the queue
     * @param albumId The _ID of the album
     */
    public void addAlbumToQueue(long albumId) {
        LogHelper.i(TAG, "addAlbumToQueue id=", albumId);
        // get all the new tracks to add. Will add all tracks in the same category as the chosen track
        Iterable<MediaMetadataCompat> tracks;
        tracks = mMusicProvider.getMusicsByAlbum(Long.toString(albumId));

        List<MediaSessionCompat.QueueItem> newQueueItems = new ArrayList<>();
        int count = 0;
        for (MediaMetadataCompat track : tracks) {
            // TODO: Here (and for artists, and tracks) we start with a track ID from the queue item,
            // then create and store a hierarchical media ID which is then parsed
            // To get the track ID...
            // It is read from the queueitem key METADATA_KEY_MEDIA_ID and then stored back into the same KEY!
            // The whole thing should be refactored to just use the track ID, with no hierarchy
            // (plus the hierarchy is basically meaningless here)...
            CharSequence trackId = track.getText(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
            MediaMetadataCompat trackCopy = new MediaMetadataCompat.Builder(track)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, /*"ALBUM/ALBUM|"+*/""+trackId/*Long.toString(albumId)*/)
                    .build();

            // We don't expect queues to change after created, so we use the item index as the
            // queueId. Any other number unique in the queue would work.
            MediaSessionCompat.QueueItem item = new MediaSessionCompat.QueueItem(
                    trackCopy.getDescription(), count++);
            newQueueItems.add(item);
        }

        LogHelper.i(TAG, newQueueItems.size(), " new tracks");
        mPlayingQueue.addAll(0,newQueueItems); // add at front of queue
        mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
    }

    public void addArtistToQueue(long artistId) {
        LogHelper.i(TAG, "addArtistToQueue id=", artistId);
        // get all the new tracks to add. Will add all tracks in the same category as the chosen track
        Iterable<MediaMetadataCompat> tracks;
        tracks = mMusicProvider.getMusicsByArtist(Long.toString(artistId));
        List<MediaSessionCompat.QueueItem> newQueueItems = new ArrayList<>();
        int count = 0;
        for (MediaMetadataCompat track : tracks) {
            CharSequence trackId = track.getText(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);

            MediaMetadataCompat trackCopy = new MediaMetadataCompat.Builder(track)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, /*"ARTIST/ARTIST|"+*/""+trackId /*Long.toString(artistId)*/)
                    .build();

            // We don't expect queues to change after created, so we use the item index as the
            // queueId. Any other number unique in the queue would work.
            MediaSessionCompat.QueueItem item = new MediaSessionCompat.QueueItem(
                    trackCopy.getDescription(), count++);
            newQueueItems.add(item);
        }

        LogHelper.i(TAG, newQueueItems.size(), " new tracks");
        mPlayingQueue.addAll(0, newQueueItems);
        mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
    }

    public void addTrackToQueue(long trackId) {
        String stringTrackId = Long.toString(trackId);
        MediaMetadataCompat track = mMusicProvider.getMusic(stringTrackId);

        MediaMetadataCompat trackCopy = new MediaMetadataCompat.Builder(track)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, /*"TRACK/TRACK|"*/""+trackId)
                .build();


        MediaSessionCompat.QueueItem item = new MediaSessionCompat.QueueItem(
                trackCopy.getDescription(), QueueHelper.count++);

        mPlayingQueue.add(0,item); // Add at top of queue
        mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);
    }
    /**
     * My own implementation of the example based on setQueueFromMusic.
     * Takes an input media ID from the 'music browser' and adds songs to the queue
     * (The standard implementation replaces the queue)
     */

        public void updateMetadata() {
        LogHelper.i(TAG, "upda  teMetadata");
        MediaSessionCompat.QueueItem currentMusic = getCurrentMusic();
        if (currentMusic == null) {
            LogHelper.i(TAG, "current Music = null");
            mListener.onMetadataRetrieveError();
            return;
        }

        final String mediaId = currentMusic.getDescription().getMediaId();
        MediaMetadataCompat metadata = mMusicProvider.getMusic(mediaId);
        if (metadata == null) {
            throw new IllegalArgumentException("Invalid mediaId " + mediaId);
        }

        mListener.onMetadataChanged(metadata);
        // call onqueue updated, as well as on metadata changed
        mListener.onQueueUpdated("AlbumTitle", mPlayingQueue);

        // The rest of this is all about artwork, so we aren't so bothered here
        // Set the proper album artwork on the media session, so it can be shown in the
        // locked screen and in other places.
        if (metadata.getDescription().getIconBitmap() == null &&
                metadata.getDescription().getIconUri() != null) {
            String albumUri = metadata.getDescription().getIconUri().toString();
            AlbumArtCache.getInstance().fetch(albumUri, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    mMusicProvider.updateMusicArt(mediaId, bitmap, icon);

                    // If we are still playing the same music, notify the listeners:
                    MediaSessionCompat.QueueItem currentMusic = getCurrentMusic();
                    if (currentMusic == null) {
                        return;
                    }

                    //String currentPlayingId = MediaIDHelper.extractMusicIDFromMediaID(currentMusic.getDescription().getMediaId());

                    String currentPlayingMediaId = currentMusic.getDescription().getMediaId();

                    if (mediaId.equals(currentPlayingMediaId)) {
                        mListener.onMetadataChanged(mMusicProvider.getMusic(currentPlayingMediaId));
                    }
                }
            });
        }
    }

    // Easy change. No current index .. we just return 'Now playing'
    public MediaSessionCompat.QueueItem getCurrentMusic() {
        return mNowPlaying;
        /* example implementation
        if (!QueueHelper.isIndexPlayable(mCurrentIndex, mPlayingQueue)) {
            return null;
        }
        return mPlayingQueue.get(mCurrentIndex);
        */
    }

    public int getCurrentQueueSize() {
        if (mPlayingQueue == null) {
            return 0;
        }
        return mPlayingQueue.size();
    }

    protected void setCurrentQueue(String title, List<MediaSessionCompat.QueueItem> newQueue) {
        LogHelper.i(TAG, "setCurrentQueue: AlbumTitle=", title);
        mPlayingQueue = newQueue;
        // setCurrentQueue(AlbumTitle, newQueue, null);
    }


    protected void setCurrentQueue(String title, List<MediaSessionCompat.QueueItem> newQueue,
                                   String initialMediaId) {
        LogHelper.i(TAG, "setCurrentQueue: setting new queue with initial media id = ", initialMediaId);
        mPlayingQueue = newQueue;
        if (initialMediaId != null) {
            int index = 0;
            if (initialMediaId != null) {
                index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, initialMediaId);
            }
            // don't set the mCurrent index, just set the nowPlaying instead
            int currentIndex = Math.max(index, 0);
            mNowPlaying = mPlayingQueue.remove(currentIndex);
        }
        mListener.onQueueUpdated(title, newQueue);
    }

    // this is my interface
    public interface MetadataUpdateListener {
        void onMetadataChanged(MediaMetadataCompat metadata);
        void onMetadataRetrieveError();
        void onQueueUpdated(String title, List<MediaSessionCompat.QueueItem> newQueue);

        // void onCurrentQueueIndexUpdated(int queueIndex);
        void onNowPlayingChanged(MediaSessionCompat.QueueItem nowPlaying);
        void onPauseRequest();

    }
/*
    // This is from the google example
    public interface ExampleMetadataUpdateListener {
        void onMetadataChanged(MediaMetadataCompat metadata);
        void onMetadataRetrieveError();
        void onCurrentQueueIndexUpdated(int queueIndex);
        void onQueueUpdated(String title, List<MediaSessionCompat.QueueItem> newQueue);
    }
    */
}
