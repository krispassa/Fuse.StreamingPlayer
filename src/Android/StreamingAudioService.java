package com.fuse.StreamingPlayer;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;

public final class StreamingAudioService
        extends Service
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener
{

    public interface StreamingAudioClient
    {
        void OnStatusChanged();

        void OnHasPrevNextChanged();

        void OnCurrentTrackChanged();

        void OnInternalStatusChanged(int i);
    }

    public class LocalBinder extends Binder
    {
        public StreamingAudioService getService()
        {
            // Return this instance of LocalService so clients can call public methods
            return StreamingAudioService.this;
        }
    }

    MediaPlayer _player;
    MediaSession _session;
    MediaController _controller;
    MediaMetadataRetriever _metadataRetriever;

    LocalBinder _binder = new LocalBinder();
    boolean _prepared = false;

    StreamingAudioClient _streamingAudioClient;

    Track _currentTrack;
    ArrayList<Track> _playlist = new ArrayList<Track>();


    public void setAudioClient(StreamingAudioClient bgp)
    {
        _streamingAudioClient = bgp;
    }

    @Override
    public void onCreate()
    {
        Logger.Log("Android: Created new MediaPlayer");
        _player = new MediaPlayer();
        //_player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        _player.setOnErrorListener(this);
        _player.setOnPreparedListener(this);
        _player.setOnCompletionListener(this);
        _metadataRetriever = new MediaMetadataRetriever();
        initMediaSessions();
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return _binder;
    }

    AndroidPlayerState _state = AndroidPlayerState.Idle;

    void setState(AndroidPlayerState state)
    {
        _state = state;
        int intState = 0;
        switch (state)
        {
            case Idle:
                intState = 0;
                Logger.Log("AndroidStatus: Idle");
                break;
            case Initialized:
                intState = 1;
                Logger.Log("AndroidStatus: Initialized");
                break;
            case Preparing:
                intState = 2;
                Logger.Log("AndroidStatus: Preparing");
                break;
            case Prepared:
                intState = 3;
                Logger.Log("AndroidStatus: Prepared");
                break;
            case Started:
                intState = 4;
                Logger.Log("AndroidStatus: Started");
                break;
            case Stopped:
                intState = 5;
                Logger.Log("AndroidStatus: Stopped");
                break;
            case Paused:
                intState = 6;
                Logger.Log("AndroidStatus: Paused");
                break;
            case PlaybackCompleted:
                intState = 7;
                Logger.Log("AndroidStatus: PlaybackCompleted");
                break;
            case Error:
                intState = 8;
                Logger.Log("AndroidStatus: Error");
                break;
            case End:
                intState = 9;
                Logger.Log("AndroidStatus: End");
                break;
        }
        _streamingAudioClient.OnInternalStatusChanged(intState);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onPrepared(MediaPlayer mp)
    {
        _prepared = true;
        setState(AndroidPlayerState.Prepared);
        _session.setActive(true);
        _player.setLooping(false);
        _player.start();
        _session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build());
        setState(AndroidPlayerState.Started);
        buildNotification(generateAction(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE));
    }

    public void Play(Track track)
    {
        if (_prepared)
        {
            _prepared = false;
            _player.stop();
            _player.reset();
        }
        try
        {
            _player.reset();
            _state = AndroidPlayerState.Initialized;
            Logger.Log("SetDataSource: state: " + _state);
            _player.setDataSource(track.Url);
            setState(AndroidPlayerState.Preparing);
            _player.prepareAsync();
        }
        catch (Exception e)
        {
            Logger.Log("Exception while setting MediaPlayer DataSource");
        }

        _currentTrack = track;
        if (_streamingAudioClient != null)
        {
            _streamingAudioClient.OnCurrentTrackChanged();
            _streamingAudioClient.OnHasPrevNextChanged();
        }
    }

    public void Resume()
    {
        if (_prepared)
        {
            if (_player.isPlaying())
            {
                _player.seekTo(0);
            }
            else if (_prepared)
            {
                _player.start();
                setState(AndroidPlayerState.Started);
            }
            buildNotification(generateAction(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE));
        }
    }

    public int CurrentTrackIndex()
    {
        return _playlist.indexOf(_currentTrack);
    }

    public double GetCurrentPosition()
    {
        if (_prepared)
        {
            return _player.getCurrentPosition();
        }
        return 0.0;
    }

    public double GetCurrentTrackDuration()
    {
        if (_prepared)
        {
            Logger.Log("TrackDur: " + _state);
            return _player.getDuration();
        }
        return 0.0;
    }

    public Track GetCurrentTrack()
    {
        return _currentTrack;
    }

    public void Seek(int milliseconds)
    {
        if (_prepared)
        {
            _player.seekTo(milliseconds);
        }
    }

    public void SetPlaylist(Track[] tracks)
    {
        _playlist.clear();
        for (int i = 0; i < tracks.length; i++)
        {
            _playlist.add(tracks[i]);
        }
        _streamingAudioClient.OnHasPrevNextChanged();
    }

    public void AddTrack(Track track)
    {
        _playlist.add(track);
    }

    public void Next()
    {
        if (HasNext())
        {
            Play(_playlist.get(CurrentTrackIndex() + 1));
        }
        _streamingAudioClient.OnHasPrevNextChanged();
        buildNotification(generateAction(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE));
    }

    public void Previous()
    {
        if (HasPrevious())
        {
            Play(_playlist.get(CurrentTrackIndex() - 1));
        }
        _streamingAudioClient.OnHasPrevNextChanged();
        buildNotification(generateAction(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE));
    }

    public boolean HasNext()
    {
        int currentIndex = CurrentTrackIndex();
        int playlistSize = _playlist.size();
        return currentIndex > -1 && currentIndex < playlistSize - 1;
    }

    public boolean HasPrevious()
    {
        int currentIndex = CurrentTrackIndex();
        return currentIndex > 0;
    }

    public void Pause()
    {
        if (_state == AndroidPlayerState.Started)
        {
            _player.pause();
            setState(AndroidPlayerState.Paused);
            buildNotification(generateAction(android.R.drawable.ic_media_play, "Play", ACTION_PLAY));
        }

    }

    public void Stop()
    {
        _prepared = false;
        _player.stop();
        _player.reset();
        setState(AndroidPlayerState.Idle);
        buildNotification(generateAction(android.R.drawable.ic_media_play, "Play", ACTION_PLAY));
    }

    @Override
    public void onCompletion(MediaPlayer mp)
    {
        Logger.Log("Android track completed");
        Next();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra)
    {
        Logger.Log("Error while mediaplayer in state: " + _state);
        Logger.Log("We did get an error: what:" + what + ", extra:" + extra);
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        handleIntent(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    public static final String ACTION_PLAY = "action_play";
    public static final String ACTION_PAUSE = "action_pause";
    public static final String ACTION_REWIND = "action_rewind";
    public static final String ACTION_FAST_FORWARD = "action_fast_foward";
    public static final String ACTION_NEXT = "action_next";
    public static final String ACTION_PREVIOUS = "action_previous";
    public static final String ACTION_STOP = "action_stop";

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void handleIntent(Intent intent)
    {
        if (intent == null) return;
        if (intent.getAction() == null) return;

        String action = intent.getAction();
        if (action.equalsIgnoreCase(ACTION_PLAY))
        {
            _controller.getTransportControls().play();
        }
        else if (action.equalsIgnoreCase(ACTION_PAUSE))
        {
            _controller.getTransportControls().pause();
        }
        else if (action.equalsIgnoreCase(ACTION_FAST_FORWARD))
        {
            _controller.getTransportControls().fastForward();
        }
        else if (action.equalsIgnoreCase(ACTION_REWIND))
        {
            _controller.getTransportControls().rewind();
        }
        else if (action.equalsIgnoreCase(ACTION_PREVIOUS))
        {
            _controller.getTransportControls().skipToPrevious();
        }
        else if (action.equalsIgnoreCase(ACTION_NEXT))
        {
            _controller.getTransportControls().skipToNext();
        }
        else if (action.equalsIgnoreCase(ACTION_STOP))
        {
            _controller.getTransportControls().stop();
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
    public Notification.Action generateAction(int icon, String title, String intentAction)
    {
        Intent intent = new Intent(getApplicationContext(), StreamingAudioService.class);
        intent.setAction(intentAction);
        PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 1, intent, 0);
        return new Notification.Action.Builder(icon, title, pendingIntent).build();
    }


    private void buildNotification(Notification.Action action)
    {
        ArtworkMediaNotification.Notify(_currentTrack, action, _session, this);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void initMediaSessions()
    {
        _session = new MediaSession(getApplicationContext(), "FuseStreamingPlayerSession");
        _controller = new MediaController(getApplicationContext(), _session.getSessionToken());

        _session.setCallback(new MediaSession.Callback()
         {
             @Override
             public void onPlay()
             {
                 super.onPlay();
                 Resume();
             }

             @Override
             public void onPause()
             {
                 super.onPause();
                 Pause();
             }

             @Override
             public void onSkipToNext()
             {
                 super.onSkipToNext();
                 Logger.Log("Skipping from media notification: " + _currentTrack.Name);
                 Next();
             }

             @Override
             public void onSkipToPrevious()
             {
                 super.onSkipToPrevious();
                 Previous();
             }

             @Override
             public void onStop()
             {
                 super.onStop();
                 Stop();
                 NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
                 notificationManager.cancel(1);
                 Intent intent = new Intent(getApplicationContext(), StreamingAudioService.class);
                 stopService(intent);
             }
         });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public boolean onUnbind(Intent intent)
    {
        _session.release();
        return super.onUnbind(intent);
    }
}
