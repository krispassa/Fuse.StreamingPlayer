using Uno;
using Uno.UX;
using Uno.Threading;
using Uno.Permissions;
using Fuse.Scripting;
using Uno.Collections;
using Uno.Compiler.ExportTargetInterop;

namespace StreamingPlayer
{
    [ForeignInclude(Language.Java,
                    "com.fuse.StreamingPlayer.StreamingAudioService",
                    "com.fuse.StreamingPlayer.Track",
                    "android.media.MediaPlayer",
                    "android.media.AudioManager",
                    "java.lang.Exception",
                    "android.app.Activity",
                    "android.content.ComponentName",
                    "android.content.Context",
                    "android.content.Intent",
                    "android.content.ServiceConnection",
                    "android.os.Bundle",
                    "android.os.IBinder",
                    "android.os.Parcel",
                    "android.os.RemoteException")]
    [Require("AndroidManifest.ApplicationElement", "<service android:name=\"com.fuse.StreamingPlayer.StreamingAudioService\" />")]
    extern(Android) class StreamingPlayerAndroidImpl : IStreamingPlayer
    {
        //------------------------------------------------------------
        // State

        Java.Object _service; //StreamingAudioService
        Java.Object _serviceConnection; // ServiceConnection
        Java.Object _binder; // StreamingAudioService.LocalBinder
        PlayerStatus _status = PlayerStatus.Stopped;

        public bool IsConnected
        {
            get { return _binder != null; }
        }

        public bool HasNext
        {
            get { return IsConnected ? GetHasNextImpl(_service) : false; }
        }

        public bool HasPrevious
        {
            get { return IsConnected ? GetHasPreviousImpl(_service) : false; }
        }

        public double Progress
        {
            get { return IsConnected ? (GetProgress(_service) / 1000.0) : 0; }
        }

        public double Duration
        {
            get { return IsConnected ? (GetDuration(_service) / 1000.0) : 0; }
        }

        public Track CurrentTrack
        {
            get
            {
                if (IsConnected) {
                    var currentTrackJava = GetCurrentTrackImpl(_service);
                    if (currentTrackJava != null)
                    {
                        var id = TrackAndroidImpl.GetId(currentTrackJava);
                        var name = TrackAndroidImpl.GetName(currentTrackJava);
                        var artist = TrackAndroidImpl.GetArtist(currentTrackJava);
                        var url = TrackAndroidImpl.GetUrl(currentTrackJava);
                        var artworkUrl = TrackAndroidImpl.GetArtworkUrl(currentTrackJava);
                        var duration = TrackAndroidImpl.GetDuration(currentTrackJava);
                        var ret = new Track(id, name, artist, url, artworkUrl, duration);
                        return ret;
                    }
                }
                return null;
            }
        }

        public PlayerStatus Status
        {
            get { return _status; }
            private set
            {
                var last = _status;
                _status = value;
                if (last != value)
                    OnStatusChanged();
            }
        }

        //------------------------------------------------------------
        // Initializing

        static StreamingPlayerAndroidImpl _current;
        static bool _permittedToPlay = false;
        public StreamingPlayerAndroidImpl()
        {
            debug_log("Created streamingplayerandroidimpl");

            InternalStatusChanged(0);

            if (_current != null)
                _current.Stop();

            _current = this;
        }

        void OnPermitted(PlatformPermission permission)
        {
            _permittedToPlay = true;
        }

        void OnRejected(Exception e)
        {
            debug_log "StreamingPlayer was not given permissions to play local files: " + e.Message;
            _permittedToPlay = false;
        }

        void CreateService()
        {
            var permissionPromise = Permissions.Request(Permissions.Android.WRITE_EXTERNAL_STORAGE);
            permissionPromise.Then(OnPermitted, OnRejected);

            _serviceConnection = CreateServiceConnection();
            StartService(_serviceConnection);
        }

        [Foreign(Language.Java)]
        void StartService(Java.Object serviceConnection)
        @{
            ServiceConnection scon = (ServiceConnection)serviceConnection;
            try
            {
                Activity a = com.fuse.Activity.getRootActivity();
                Intent intent = new Intent(a, StreamingAudioService.class);
                intent.setAction(StreamingAudioService.ACTION_PLAY);
                a.bindService(intent, scon, Context.BIND_AUTO_CREATE);
                a.startService(intent);
            }
            catch (Exception e)
            {
                android.util.Log.d("StreamingPlayer", "We were not able to create a media player :S" + e.toString());
            }
        @}

        [Foreign(Language.Java)]
        Java.Object CreateServiceConnection()
        @{
            return new ServiceConnection()
            {
                public void onServiceConnected(ComponentName className, IBinder service)
                {
                    // Because we have bound to an explicit
                    // service that is running in our own process, we can
                    // cast its IBinder to a concrete class and directly access it.
                    StreamingAudioService.LocalBinder binder = (StreamingAudioService.LocalBinder)service;
                    @{StreamingPlayerAndroidImpl:Of(_this)._binder:Set(binder)};

                    StreamingAudioService ourService = binder.getService();
                    @{StreamingPlayerAndroidImpl:Of(_this)._service:Set(ourService)};

                    ourService.setAudioClient(
                        new StreamingAudioService.StreamingAudioClient()
                        {
                            public void OnStatusChanged()
                            {
                                @{StreamingPlayerAndroidImpl:Of(_this).OnStatusChanged():Call()};
                            }
                            public void OnHasPrevNextChanged()
                            {
                                @{StreamingPlayerAndroidImpl:Of(_this).HasPrevNextChanged():Call()};
                            }
                            public void OnCurrentTrackChanged()
                            {
                                @{StreamingPlayerAndroidImpl:Of(_this).OnCurrentTrackChanged():Call()};
                            }
                            public void OnInternalStatusChanged(int i)
                            {
                                @{StreamingPlayerAndroidImpl:Of(_this).InternalStatusChanged(int):Call(i)};
                            }
                        });
                    @{StreamingPlayerAndroidImpl:Of(_this).ConnectedToBackgroundService():Call()};
                }
                // Called when the connection with the service disconnects unexpectedly
                public void onServiceDisconnected(ComponentName className)
                {
                    debug_log("Music player handle service disconnection");
                }
            };
        @}

        //------------------------------------------------------------
        // Events

        public event Action CurrentTrackChanged;
        public event Action<bool> HasNextChanged;
        public event Action<bool> HasPreviousChanged;
        public event StatusChangedHandler StatusChanged;

        void OnStatusChanged()
        {
            debug_log("Status changed (uno): " + Status);
            if (StatusChanged != null)
                StatusChanged(Status);
        }

        void HasPrevNextChanged()
        {
            if (HasNextChanged != null)
                HasNextChanged(HasNext);
            if (HasPreviousChanged != null)
                HasPreviousChanged(HasPrevious);
        }

        void OnCurrentTrackChanged()
        {
            debug_log("Current track changed");
            if (CurrentTrackChanged != null)
                CurrentTrackChanged();
        }

        void InternalStatusChanged(int i)
        {
            // mapping the enum in AndroidPlayerState.java to uno
            switch (i)
            {
                case 0:
                case 1:
                    Status = PlayerStatus.Stopped;
                    break;
                case 2:
                case 3:
                    Status = PlayerStatus.Loading;
                    break;
                case 4:
                    Status = PlayerStatus.Playing;
                    break;
                case 5:
                    Status = PlayerStatus.Stopped;
                    break;
                case 6:
                    Status = PlayerStatus.Paused;
                    break;
                case 7:
                    Status = PlayerStatus.Stopped;
                    break;
                case 8:
                    Status = PlayerStatus.Error;
                    break;
                case 9:
                    Status = PlayerStatus.Stopped;
                    break;
            }
        }

        [Foreign(Language.Java)]
        Java.Object ToJavaTrack(int id, string name, string artist, string url, string artworkUrl, double duration)
        @{
            return new Track(id, name, artist, url, artworkUrl, duration);
        @}

        bool _pendingPlay = false;
        Track _pendingTrack;
        public void Play(Track track)
        {
            if (_service == null)
                CreateService();

            if (IsConnected)
            {
                var javaTrack = ToJavaTrack(track.Id, track.Name, track.Artist, track.Url, track.ArtworkUrl, track.Duration);
                Status = PlayerStatus.Loading;
                PlayImpl(_service, javaTrack);
                _pendingPlay = false;
            } else {
                _pendingPlay = true;
                _pendingTrack = track;
            }
        }

        [Foreign(Language.Java)]
        void PlayImpl(Java.Object service, Java.Object track)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Play((Track)track);
        @}


        void ConnectedToBackgroundService()
        {
            if (_tempPlaylist != null) {
                SetPlaylist(_tempPlaylist);
                _tempPlaylist = null;
            }
            if (_pendingPlay)
                Play(_pendingTrack);
            HasPrevNextChanged();
        }



        public void Resume()
        {
            if (IsConnected)
            {
                Status = PlayerStatus.Playing;
                ResumeImpl(_service);
            }
        }

        [Foreign(Language.Java)]
        void ResumeImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Resume();
        @}

        public void Seek(double toProgress)
        {
            if (IsConnected)
            {
                var timeMS = (int)(Duration * toProgress * 1000);
                SeekImpl(_service, timeMS);
            }
        }

        [Foreign(Language.Java)]
        void SeekImpl(Java.Object service, int timeMS)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Seek(timeMS);
        @}

        [Foreign(Language.Java)]
        double GetDuration(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            return sService.GetCurrentTrackDuration();
        @}

        [Foreign(Language.Java)]
        double GetProgress(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            return sService.GetCurrentPosition();
        @}

        public void Pause()
        {
            if (IsConnected)
            {
                Status = PlayerStatus.Paused;
                PauseImpl(_service);
            }
        }

        [Foreign(Language.Java)]
        void PauseImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Pause();
        @}

        public void Stop()
        {
            if (IsConnected)
            {
                Status = PlayerStatus.Paused;
                StopImpl(_service);
            }
        }

        [Foreign(Language.Java)]
        void StopImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Stop();
        @}


        Track[] _tempPlaylist;
        //int id, string name, string url, string artworkUrl, double duration
        public void SetPlaylist(Track[] tracks)
        {
            if (_service == null)
                CreateService();

            if (IsConnected)
            {
                int[] ids = new int[tracks.Length];
                string[] names = new string[tracks.Length];
                string[] artists = new string[tracks.Length];
                string[] urls = new string[tracks.Length];
                string[] artworkUrls = new string[tracks.Length];
                double[] durations = new double[tracks.Length];

                for (int i = 0; i < tracks.Length; i++) {
                    var t = tracks[i];
                    ids[i] = t.Id;
                    names[i] = t.Name;
                    artists[i] = t.Artist;
                    urls[i] = t.Url;
                    artworkUrls[i] = t.ArtworkUrl;
                    durations[i] = t.Duration;
                }
                debug_log("Android: set current playlist");
                SetPlaylistImpl(_service, ids, names, artists, urls, artworkUrls, durations);
            } else {
                debug_log("Android: caching as _tempPlaylist");
                _tempPlaylist = tracks;
            }
        }

        [Foreign(Language.Java)]
        void SetPlaylistImpl(Java.Object service,
                             int[] ids,
                             string[] names,
                             string[] artists,
                             string[] urls,
                             string[] artworkUrls,
                             double[] durations)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            int[] i = ids.copyArray();
            String[] n = names.copyArray();
            String[] art = artists.copyArray();
            String[] u = urls.copyArray();
            String[] a = artworkUrls.copyArray();
            double[] d = durations.copyArray();

            Track[] tracks = new Track[i.length];

            for (int j = 0; j < i.length; j++) {
                Track t = new Track(i[j], n[j], art[j], u[j], a[j], d[j]);
                tracks[j] = t;
            }

            sService.SetPlaylist(tracks);
        @}

        public int Next()
        {
            if (IsConnected)
                return NextImpl(_service);
            else
                return 0;
        }

        [Foreign(Language.Java)]
        int NextImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Next();
            return sService.CurrentTrackIndex();
        @}

        public int Previous()
        {
            if (IsConnected)
                return PreviousImpl(_service);
            else
                return 0;
        }

        [Foreign(Language.Java)]
        int PreviousImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            sService.Previous();
            return sService.CurrentTrackIndex();
        @}

        public void AddTrack(Track track)
        {
            if (IsConnected)
                AddTrackImpl(_service, track);
        }
        [Foreign(Language.Java)]
        void AddTrackImpl(Java.Object service, object track)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            int id = @{Track:Of(track).Id:Get()};
            String name = @{Track:Of(track).Name:Get()};
            String artist = @{Track:Of(track).Artist:Get()};
            String url = @{Track:Of(track).Url:Get()};
            String artworkUrl = @{Track:Of(track).ArtworkUrl:Get()};
            double duration = @{Track:Of(track).Duration:Get()};
            Track jTrack = new Track(id, name, artist, url, artworkUrl, duration);
            sService.AddTrack(jTrack);
        @}

        [Foreign(Language.Java)]
        Java.Object GetCurrentTrackImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            return sService.GetCurrentTrack();
        @}

        [Foreign(Language.Java)]
        bool GetHasNextImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            return sService.HasNext();
        @}

        [Foreign(Language.Java)]
        bool GetHasPreviousImpl(Java.Object service)
        @{
            StreamingAudioService sService = (StreamingAudioService)service;
            return sService.HasPrevious();
        @}

    }
}
