package se.slashat.slashapp.androidservice;

import java.io.Serializable;

import se.slashat.slashapp.MainActivity;
import se.slashat.slashapp.R;
import se.slashat.slashapp.util.Network;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.Service;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.RemoteControlClient;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.widget.RemoteViews;

/**
 * EpisodePlayerService that plays a selected episode in the background. TODO:
 * Wake locks, MediaPlayerState handling, seek in file, interaction with
 * buttons.
 *
 * @author nicklas.lof
 *
 */

public class EpisodePlayer extends Service implements OnPreparedListener, OnCompletionListener, Serializable, AudioManager.OnAudioFocusChangeListener {

	private static final String PREF_LAST_PLAYED_POSITION = "lastPlayedPosition";
	private static final String PREF_LAST_PLAYED_STREAM_URL = "lastPlayedStreamUrl";
	private static final String PREF_LAST_PLAYED_EPISODE_NAME = "lastPlayedEpisodeName";
	private static final String PREF_EPISODE_PLAYER = "EpisodePlayer";
    public static final String PLAYPAUSE_COMMAND = "se.slashat.slashapp.PLAYPAUSE_COMMAND";
	private static final long serialVersionUID = 1L;
    private static BroadcastReceiver broadcastReceiver;
    private MediaPlayer mediaPlayer;
	private EpisodePlayerBinder binder;
	private boolean episodePlayerBound;
	private static EpisodePlayer episodePlayer;
	private Notification notification;
	private String episodeName;
	private ProgressDialog progressDialog;
	private static PlayerInterface playerInterface;
	private static DurationUpdaterThread durationUpdaterThread;
	private static boolean paused = false;
	private static String lastPlayedEpisodeName;
	private static String lastPlayedStreamUrl;
	private static int lastPlayedPosition;
	private String streamUrl;
	private int startposition;
	private static Context callingContext;
    private boolean live;
    private AudioManager audioManager;
    private ComponentName mediaControllerReciever;
    private RemoteControlClient remoteClient;

    @Override
    public void onAudioFocusChange(int i) {

    }

    public class EpisodePlayerBinder extends Binder implements Serializable {
		/**
		 *
		 */
		private static final long serialVersionUID = 1L;

		public EpisodePlayer getService() {
			return EpisodePlayer.this;
		}
	}

	/**
	 * Callback interface to inform caller about duration updates and update
	 * media player status.
	 *
	 * @author Nicklas Löf
	 *
	 */
	public interface PlayerInterface {
		public void durationUpdate(int seekMax, int seek);

		public void onMediaPaused(String episodeName);

		public void onMediaStopped(String episodeName, boolean EOF);

		public void onMediaPlaying(String episodeName);
	}

	public EpisodePlayer() {
	}

	/**
	 * Initalizes the episoderplayer and binds it as a service. It seems to need
	 * the applicationContext from the MainActivity to work correctly.
	 *
	 * @param context
	 */
	public static void initalize(Context context, PlayerInterface playerInterface) {
        episodePlayer = new EpisodePlayer();
		episodePlayer.bindToEpisodePlayerService(context, playerInterface);
		callingContext = context;
		setupLastPlayed();
    }

	private static void setupLastPlayed() {
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingContext);
		lastPlayedEpisodeName = sharedPreferences.getString(PREF_LAST_PLAYED_EPISODE_NAME, "");
		lastPlayedStreamUrl = sharedPreferences.getString(PREF_LAST_PLAYED_STREAM_URL, "");
		lastPlayedPosition = sharedPreferences.getInt(PREF_LAST_PLAYED_POSITION, 0);

	}

	/**
	 * Gets the episodeplayer.
	 *
	 * @return
	 */
	public static EpisodePlayer getEpisodePlayer() {
		if (episodePlayer == null) {
			throw new IllegalStateException("Please initalize the EpisodePlayer service before trying to use it");
		}
		return episodePlayer;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		binder = new EpisodePlayerBinder();
        IntentFilter filter = new IntentFilter();
        filter.setPriority(Integer.MAX_VALUE);
        filter.addAction(PLAYPAUSE_COMMAND);
        broadcastReceiver = new MediaControllerIntentReceiver();
        registerReceiver(broadcastReceiver, filter);
    }

    public void playLiveStream(){
        playStream("http://slashat.se:8000/","Slashat.se - live",0,null,callingContext, true);
    }

	/**
	 * Starts a new Mediaplayer for the selected URL.
	 *
     * @param streamUrl
     * @param fullEpisodeName
     * @param position
     */

    public void playStream(String streamUrl, String fullEpisodeName, int position, ProgressDialog progressDialog, Context context) {
        playStream(streamUrl, fullEpisodeName, position, progressDialog,context, false);
    }

	@SuppressLint("NewApi")
    private void playStream(String streamUrl, String fullEpisodeName, int position, ProgressDialog progressDialog, Context context, boolean live) {
        if (Network.isNetworkAvailable()) {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(context);
                progressDialog.setTitle("Buffrar avsnitt");
                progressDialog.setMessage(fullEpisodeName);
                progressDialog.show();
            }

            this.episodeName = fullEpisodeName;
            this.streamUrl = streamUrl;
            this.progressDialog = progressDialog;
            this.startposition = position;
            this.live = live;

            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            if (streamUrl == null) {
                stopPlay(); // Temporary to stop playing until buttons are
                // implemented.
                progressDialog.dismiss();
                return;
            }
            try {
                mediaPlayer.setDataSource(streamUrl);
            } catch (Exception e) {
                e.printStackTrace();
            }

            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.prepareAsync();
        }

	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

        System.out.println("START COMMAND!!!!!");

        return START_STICKY;
	}

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onPrepared(MediaPlayer mp) {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mediaControllerReciever = new ComponentName(getApplicationContext(), MediaControllerIntentReceiver.class);

        Intent li = new Intent(Intent.ACTION_MEDIA_BUTTON);
        li.setComponent(mediaControllerReciever);


        PendingIntent lpi = PendingIntent.getBroadcast(getApplicationContext(), 0, li, 0);
        remoteClient = new RemoteControlClient(lpi);
        remoteClient.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE);


        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.registerMediaButtonEventReceiver(mediaControllerReciever);
            audioManager.registerRemoteControlClient(remoteClient);
        }
        startPlay();
    }

	@Override
	public void onCompletion(MediaPlayer mp) {
        if (durationUpdaterThread != null){
		    durationUpdaterThread.interupt();
        }

        if (progressDialog.isShowing()){
            progressDialog.dismiss();
        }

		playerInterface.onMediaStopped(episodeName, !live);
	}

	/**
	 * Start play the media set in initializePlayer() and plays the media in the
	 * background.
	 */
	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void startPlay() {

        broadcastChange();
		mediaPlayer.start();
		progressDialog.dismiss();

		durationUpdaterThread = new DurationUpdaterThread(mediaPlayer, playerInterface);
		durationUpdaterThread.start();

		playerInterface.onMediaPlaying(episodeName);
		if (startposition > 0) {
			mediaPlayer.seekTo(startposition);
		}

        remoteClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

        updateNotificationAndMediaMetadata();

	}

	/**
	 * Stops playing the current media and removes the player from the
	 * background
	 */
	public void stopPlay() {
		if (mediaPlayer != null) {
			mediaPlayer.stop();
			unsetNotification();
		}
		paused = false;
		playerInterface.onMediaStopped(episodeName, false);

		saveLastPlayerPrefs();

	}
    public void broadcastChange() {
        Intent i=new Intent("com.android.music.playstatechanged");

         		i.putExtra("artist", episodeName);
         		i.putExtra("album", episodeName);
         		i.putExtra("track", episodeName);
         		i.putExtra("playing", isPlaying());

         		sendStickyBroadcast(i);
         	}

	private void saveLastPlayerPrefs() {
		if (callingContext != null && mediaPlayer != null) {
			Editor edit = PreferenceManager.getDefaultSharedPreferences(callingContext).edit();
			edit.putString(PREF_LAST_PLAYED_STREAM_URL, streamUrl);
			edit.putString(PREF_LAST_PLAYED_EPISODE_NAME, episodeName);
			edit.putInt(PREF_LAST_PLAYED_POSITION, mediaPlayer.getCurrentPosition());
			edit.commit();
		}
	}

	public void seek(int position) {
		mediaPlayer.seekTo(position);
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void pause() {
		paused = true;
		if (mediaPlayer != null) {
			mediaPlayer.pause();
			saveLastPlayerPrefs();
            remoteClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
            updateNotificationAndMediaMetadata();
		}
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void resume() {
		paused = false;
		mediaPlayer.start();
        remoteClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
        updateNotificationAndMediaMetadata();
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void updateNotificationAndMediaMetadata() {

        Intent playpause = new Intent(PLAYPAUSE_COMMAND);
        PendingIntent piPlayPause = PendingIntent.getBroadcast(this, 0, playpause, PendingIntent.FLAG_UPDATE_CURRENT);

        RemoteControlClient.MetadataEditor remoteEditor = remoteClient.editMetadata(true);
        remoteEditor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, BitmapFactory.decodeResource(getResources(), R.drawable.logo));
        remoteEditor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, "Slashat.se");
        remoteEditor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, episodeName);
        remoteEditor.apply();

        RemoteViews remoteView = new RemoteViews(getPackageName(), R.layout.notification);
        remoteView.setTextViewText(R.id.title, "Slashat.se");
        remoteView.setTextViewText(R.id.content, episodeName);

        if (isPlaying()){
            remoteView.setImageViewResource(R.id.playpause,R.drawable.ic_media_pause);
        }else{
            remoteView.setImageViewResource(R.id.playpause,R.drawable.ic_media_play);
        }

        remoteView.setImageViewBitmap(R.id.icon, BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
        remoteView.setOnClickPendingIntent(R.id.playpause, piPlayPause);


        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getApplicationContext());
        notificationBuilder.setContentIntent(pendingIntent);
        notificationBuilder.setContent(remoteView);
        notificationBuilder.setSmallIcon(R.drawable.ic_launcher);
        notification = notificationBuilder.build();
        broadcastChange();
        startForeground(1, notification);
    }

	private void unsetNotification() {
		stopForeground(true);
	}

	public boolean isPlaying() {
		return (mediaPlayer == null) ? false : mediaPlayer.isPlaying();
	}

	public boolean isPaused() {
		return paused;
	}

	public boolean isMediaInitalized() {
		return mediaPlayer != null;
	}

	public String getCurrentPlayingEpisodeName() {
		return episodeName;
	}

	public String getLastPlayedEpisodeName() {
		return lastPlayedEpisodeName;
	}

	public int getLastPlayedPosition() {
		return lastPlayedPosition;
	}

	public String getLastPlayedStreamUrl() {
		return lastPlayedStreamUrl;
	}

	private void bindToEpisodePlayerService(Context context, PlayerInterface pi) {
		Intent intent = new Intent(context, EpisodePlayer.class);
		EpisodePlayer.playerInterface = pi;
		if (isEpisodePlayerRunning(context)) {
			context.bindService(intent, episodePlayerConnection, Context.BIND_AUTO_CREATE);
			if (durationUpdaterThread != null) {
				durationUpdaterThread.setPlayerInterface(pi);
			}
		} else {
			context.startService(intent);
			context.bindService(intent, episodePlayerConnection, Context.BIND_AUTO_CREATE);
		}
	}

	/**
	 * Checks if the Episode Player Service is running or not
	 *
	 * @return
	 */
	private boolean isEpisodePlayerRunning(Context context) {
		ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			if (this.getClass().getName().equals(service.service.getClassName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The connection to the Episode player service used to control the service
	 * itself (not the player).
	 */
	private ServiceConnection episodePlayerConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			EpisodePlayerBinder binder = (EpisodePlayerBinder) service;
			episodePlayer = binder.getService();
			episodePlayerBound = true;

		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			episodePlayerBound = false;
		}
	};

	/**
	 * Stop the Episode player service.
	 */

	private void unBindEpisodePlayerService() {
		if (episodePlayerBound) {
			episodePlayer.stopPlay();
			getApplicationContext().unbindService(episodePlayerConnection);
			episodePlayerBound = false;
		}

		Intent intent = new Intent(this, EpisodePlayer.class);
		getApplicationContext().stopService(intent);
	}

	/**
	 * Background thread that runs every second and sends duration updates to
	 * the PlayerInterface.
	 *
	 * @author Nicklas Löf
	 *
	 */
	public static class DurationUpdaterThread extends Thread {

		private PlayerInterface playerInterface;
		private MediaPlayer mediaPlayer;
		private boolean interupt;
		private Object sync = new Object(); // Sync the player interface to
											// prevent that we don't update it
											// while it's being updated.

		public DurationUpdaterThread(MediaPlayer mediaPlayer, PlayerInterface playerInterface) {
			this.mediaPlayer = mediaPlayer;
			this.playerInterface = playerInterface;
		}

		public void interupt() {
			interupt = true;
		}

		public void setPlayerInterface(PlayerInterface pi) {
			synchronized (sync) {
				this.playerInterface = pi;
			}
		}

		@Override
		public void run() {
			while (!interupt) {
				try {
					Thread.sleep(1000);
					if (!mediaPlayer.isPlaying() && !paused) {
						interupt = true;
					}
					if (playerInterface != null && !paused) {
						synchronized (sync) {
							playerInterface.durationUpdate(mediaPlayer.getDuration(), mediaPlayer.getCurrentPosition());
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			synchronized (sync) {
				if (playerInterface != null) {
					playerInterface.durationUpdate(0, 0);
				}
			}
		}
	}
}
