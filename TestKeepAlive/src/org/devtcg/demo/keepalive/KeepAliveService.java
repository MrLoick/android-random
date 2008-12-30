/*
 * $Id$
 *
 * Copyright (C) 2008 Josh Guilfoyle <jasta@devtcg.org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package org.devtcg.demo.keepalive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

public class KeepAliveService extends Service
{
	public static final String TAG = "KeepAliveService";

	private static final String HOST = "jasta.dyndns.org";
	private static final int PORT = 50000;

	private static final String ACTION_START = "org.devtcg.demo.keepalive.START";
	private static final String ACTION_STOP = "org.devtcg.demo.keepalive.STOP";
	private static final String ACTION_KEEPALIVE = "org.devtcg.demo.keepalive.KEEP_ALIVE";
	private static final String ACTION_RECONNECT = "org.devtcg.demo.keepalive.RECONNECT";
	
	private ConnectionLog mLog;

	private ConnectivityManager mConnMan;
	private NotificationManager mNotifMan;

	private boolean mStarted;
	private ConnectionThread mConnection;

	private static final long KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

	private static final long INITIAL_RETRY_INTERVAL = 1000 * 5;
	private static final long MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 2;

	private SharedPreferences mPrefs;

	private static final int NOTIF_CONNECTED = 0;

	public static void actionStart(Context ctx)
	{
		Intent i = new Intent(ctx, KeepAliveService.class);
		i.setAction(ACTION_START);
		ctx.startService(i);
	}

	public static void actionStop(Context ctx)
	{
		Intent i = new Intent(ctx, KeepAliveService.class);
		i.setAction(ACTION_STOP);
		ctx.startService(i);
	}

	@Override
	public void onCreate()
	{
		super.onCreate();

		try {
			mLog = new ConnectionLog();
			Log.i(TAG, "Opened log at " + mLog.getPath());
		} catch (IOException e) {}

		mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
		
		mConnMan =
		  (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
		
		mNotifMan =
		  (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
	}

	@Override
	public void onDestroy()
	{
		log("Service destroyed (started=" + mStarted + ")");

		if (mStarted == true)
			stop();

		try {
			if (mLog != null)
				mLog.close();
		} catch (IOException e) {}
	}

	private void log(String message)
	{
		Log.i(TAG, message);

		if (mLog != null)
		{
			try {
				mLog.println(message);
			} catch (IOException e) {}
		}
	}

	@Override
	public void onStart(Intent intent, int startId)
	{
		super.onStart(intent, startId);
		
		log("Service started with action=" + intent.getAction());

		if (intent.getAction().equals(ACTION_STOP) == true)
		{
			stop();
			stopSelf();
		}
		else if (intent.getAction().equals(ACTION_START) == true)
			start();
		else if (intent.getAction().equals(ACTION_KEEPALIVE) == true)
			keepAlive();
		else if (intent.getAction().equals(ACTION_RECONNECT) == true)
			reconnectIfNecessary();
	}
	
	@Override
	public IBinder onBind(Intent intent)
	{
		return null;
	}

	private synchronized void start()
	{
		if (mStarted == true)
		{
			Log.w(TAG, "Attempt to start connection that is already active");
			return;
		}

		mStarted = true;

		registerReceiver(mConnectivityChanged,
		  new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

		log("Connecting...");

		mConnection = new ConnectionThread(HOST, PORT);
		mConnection.start();
	}

	private synchronized void stop()
	{
		if (mStarted == false)
		{
			Log.w(TAG, "Attempt to stop connection not active.");
			return;
		}

		mStarted = false;

		unregisterReceiver(mConnectivityChanged);		
		cancelReconnect();

		if (mConnection != null)
		{
			mConnection.abort();
			mConnection = null;
		}
	}

	private synchronized void keepAlive()
	{
		try {
			if (mConnection != null)
				mConnection.sendKeepAlive();
		} catch (IOException e) {}
	}

	private void startKeepAlives()
	{
		Intent i = new Intent();
		i.setClass(this, KeepAliveService.class);
		i.setAction(ACTION_KEEPALIVE);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP,
		  System.currentTimeMillis() + KEEP_ALIVE_INTERVAL,
		  KEEP_ALIVE_INTERVAL, pi);
	}

	private void stopKeepAlives()
	{
		Intent i = new Intent();
		i.setClass(this, KeepAliveService.class);
		i.setAction(ACTION_KEEPALIVE);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.cancel(pi);
	}

	public void scheduleReconnect(long startTime)
	{
		long interval =
		  mPrefs.getLong("retryInterval", INITIAL_RETRY_INTERVAL);

		long now = System.currentTimeMillis();
		long elapsed = now - startTime;

		if (elapsed < interval)
			interval = Math.min(interval * 10, MAXIMUM_RETRY_INTERVAL);
		else
			interval = INITIAL_RETRY_INTERVAL;

		log("Rescheduling connection in " + interval + "ms.");

		mPrefs.edit().putLong("retryInterval", interval).commit();

		Intent i = new Intent();
		i.setClass(this, KeepAliveService.class);
		i.setAction(ACTION_RECONNECT);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
	}
	
	public void cancelReconnect()
	{
		Intent i = new Intent();
		i.setClass(this, KeepAliveService.class);
		i.setAction(ACTION_RECONNECT);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.cancel(pi);
	}

	private synchronized void reconnectIfNecessary()
	{
		if (mStarted == true && mConnection == null)
		{
			log("Reconnecting...");
			
			mConnection = new ConnectionThread(HOST, PORT);
			mConnection.start();
		}
	}

	private BroadcastReceiver mConnectivityChanged = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			NetworkInfo info = (NetworkInfo)intent.getParcelableExtra
			  (ConnectivityManager.EXTRA_NETWORK_INFO);

			log("Connecting changed: connected=" +
			  ((info != null && info.isConnected()) ? true : false));

			if (info != null && info.isConnected() == true)
				reconnectIfNecessary();
		}
	};
	
	private void showNotification()
	{
		Notification n = new Notification();
		
		n.flags = Notification.FLAG_NO_CLEAR |
		  Notification.FLAG_ONGOING_EVENT;

		n.icon = R.drawable.connected_notify;
		n.when = System.currentTimeMillis();

		PendingIntent pi = PendingIntent.getActivity(this, 0,
		  new Intent(this, TestKeepAlive.class), 0);

		n.setLatestEventInfo(this, "KeepAlive connected",
		  "Connected to " + HOST + ":" + PORT, pi);

		mNotifMan.notify(NOTIF_CONNECTED, n);
	}
	
	private void hideNotification()
	{
		mNotifMan.cancel(NOTIF_CONNECTED);
	}

	private class ConnectionThread extends Thread
	{
		private final Socket mSocket;
		private final String mHost;
		private final int mPort;
		
		private volatile boolean mAbort = false;

		public ConnectionThread(String host, int port)
		{
			mHost = host;
			mPort = port;
			mSocket = new Socket();
		}

		public boolean isConnected()
		{
			return mSocket.isConnected();
		}

		private boolean isNetworkAvailable()
		{
			NetworkInfo info = mConnMan.getActiveNetworkInfo();
			if (info == null)
				return false;

			return info.isConnected();
		}

		public void run()
		{
			Socket s = mSocket;
			
			long startTime = System.currentTimeMillis();
			long lastComm = startTime;

			try {
				s.connect(new InetSocketAddress(mHost, mPort), 20000);
				
				/* This is a special case for our demonstration.  The
				 * keep-alive is sent from the client side but since I'm
				 * testing it with just netcat, no response is sent from the
				 * server.  This means that we might never actually read
				 * any data even though our connection is still alive.  Most
				 * instances of a persistent TCP connection would have some
				 * sort of application-layer acknowledgement from the server
				 * and so should set a read timeout of KEEP_ALIVE_INTERVAL 
				 * plus an arbitrary timeout such as 2 minutes. */
				//s.setSoTimeout((int)KEEP_ALIVE_INTERVAL + 120000);

				log("Connection established to " + mHost + ":" + mPort);

				startKeepAlives();
				showNotification();

				InputStream in = s.getInputStream();
				OutputStream out = s.getOutputStream();

				byte[] b = new byte[1024];
				int n;

				while ((n = in.read(b)) >= 0)
				{
					lastComm = System.currentTimeMillis();
					out.write(b, 0, n);
				}

				if (mAbort == false)
					log("Server closed connection unexpectedly.");
			} catch (IOException e) {
				log("Unexpected I/O error: " + e.toString());
			} finally {
				stopKeepAlives();
				hideNotification();

				if (mAbort == true)
					log("Connection aborted, shutting down.");
				else
				{
					try {
						s.close();
					} catch (IOException e) {}

					synchronized(KeepAliveService.this) {
						mConnection = null;
					}

					/* If our local interface is still up then the connection
					 * failure must have been something intermittent.  Try
					 * our connection again later (the wait grows with each
					 * successive failure).  Otherwise we will try to
					 * reconnect when the local interface comes back. */
					if (isNetworkAvailable() == true)
						scheduleReconnect(startTime);
				}
			}
		}

		public void sendKeepAlive()
		  throws IOException
		{
			Socket s = mSocket;
			Date d = new Date();
			s.getOutputStream().write((d.toString() + "\n").getBytes());
			
			log("Keep-alive sent.");
		}

		public void abort()
		{
			log("Connection aborting.");

			mAbort = true;

			try {
				mSocket.shutdownOutput();
			} catch (IOException e) {}
			
			try {
				mSocket.shutdownInput();
			} catch (IOException e) {}
			
			try {
				mSocket.close();
			} catch (IOException e) {}

			while (true)
			{
				try {
					join();
					break;
				} catch (InterruptedException e) {}
			}
		}
	}
}