package com.fuse.StreamingPlayer;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.AsyncTask;
import android.os.Build;

import java.io.IOException;
import java.net.URL;

public final class ArtworkMediaNotification
{
    private class DownloadArtworkBitmapTask extends AsyncTask<String, Void, Bitmap>
    {

        protected Bitmap doInBackground(String... urls)
        {
            if (urls.length > 0)
            {
                try
                {
                    URL url = new URL(urls[0]);
                    Bitmap myBitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
                    return myBitmap;
                }
                catch (IOException e)
                {
                    Logger.Log("We were not able to get a bitmap of the artwork");
                }
            }
            return null;
        }

        protected void onPostExecute(Bitmap result)
        {
            if (result != null)
            {
                setArtworkBitmap(result);
            }
        }
    }

    private Track _currentTrack;
    private Notification.Action _action;
    private MediaSession _session;
    private StreamingAudioService _service;

    private ArtworkMediaNotification(Track track, MediaSession session, Notification.Action action, StreamingAudioService service)
    {
        _session = session;
        _action = action;
        _currentTrack = track;
        _service = service;
    }

    private void assignArtowrkFromUrl(String urlStr)
    {
        new DownloadArtworkBitmapTask().execute(urlStr);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void setArtworkBitmap(Bitmap bmp)
    {
        //This lets the album art be visible as the background while in the lock screen
        _session.setMetadata(new android.media.MediaMetadata.Builder()
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
                .build());

        finishNotification(bmp);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void finishNotification(Bitmap bmp)
    {
        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(_session.getSessionToken());
        style.setShowActionsInCompactView(0, 1, 2, 3, 4);

        Intent intent = new Intent(_service.getApplicationContext(), StreamingAudioService.class);
        intent.setAction(StreamingAudioService.ACTION_STOP);
        PendingIntent pendingIntent = PendingIntent.getService(_service.getApplicationContext(), 1, intent, 0);


        Notification.Builder builder = new Notification.Builder(_service);

        builder.setSmallIcon(android.R.drawable.ic_media_play);
        if (bmp != null)
        {
            Logger.Log("We are setting artwork as small icon");
            builder.setLargeIcon(bmp);
        }


        builder.setContentTitle(_currentTrack.Name)
                .setContentText(_currentTrack.Artist)
                //.setDeleteIntent(pendingIntent)
                .setStyle(style);

        builder.addAction(_service.generateAction(android.R.drawable.ic_media_previous, "Previous", StreamingAudioService.ACTION_PREVIOUS));
        builder.addAction(_action);
        builder.addAction(_service.generateAction(android.R.drawable.ic_media_next, "Next", StreamingAudioService.ACTION_NEXT));

        NotificationManager notificationManager = (NotificationManager) _service.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, builder.build());
    }

    public static void Notify(Track track, Notification.Action action, MediaSession session, StreamingAudioService service)
    {
        //Async task for getting artwork bitmap and assigning it to the media session
        ArtworkMediaNotification notification = new ArtworkMediaNotification(track, session, action, service);
        notification.assignArtowrkFromUrl(track.ArtworkUrl);
    }
}
