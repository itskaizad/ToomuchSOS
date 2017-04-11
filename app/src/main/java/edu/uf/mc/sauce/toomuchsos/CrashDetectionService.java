package edu.uf.mc.sauce.toomuchsos;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.IntegerRes;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by Kaizad on 4/9/2017.
 */
public class CrashDetectionService extends Service implements ShakeDetector.OnShakeListener
{
    private static final int REMINDER_NOTIFICATION = 11;
    private NotificationManager mNM;

    int currentSpeed;
    boolean isPiezo, isGforce;

    // The following are used for the shake detection
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private ShakeDetector mShakeDetector;
    private Vibrator vibrator;

    //Create an inner Binder class
    public class SOSBinder extends Binder
    {
        public CrashDetectionService getService()
        {
            return CrashDetectionService.this;  //Return this to access the serice later.
        }
    }

    private final IBinder binder = new SOSBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent)
    {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        //Toast.makeText(this, "STICKY service started", Toast.LENGTH_SHORT).show();
        if(intent != null && intent.getStringExtra("Speed") != null)
        {
            Log.d("SSSSSPPPEEEEEDDDDD", currentSpeed + "");
            currentSpeed = Integer.parseInt(intent.getStringExtra("Speed"));
            Toast.makeText(this, "Started with " + currentSpeed, Toast.LENGTH_SHORT).show();
        }
        super.onStartCommand(intent, flags, startId);
        return Service.START_STICKY;
    }

    @Override
    public void onCreate()
    {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        showNotification();
        Toast.makeText(this, "Service initialized", Toast.LENGTH_SHORT).show();
        super.onCreate();

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        // ShakeDetector initialization
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mShakeDetector = new ShakeDetector();
        mShakeDetector.setOnShakeListener(this);

        //Register the listener as soon as service starts
        mSensorManager.registerListener(mShakeDetector, mAccelerometer,	SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public void onShake(int count)
    {
        Toast.makeText(this, "SHHAAAAAKKKKKKEEEEEEE!", Toast.LENGTH_LONG).show();
        if(vibrator.hasVibrator())
            vibrator.vibrate(500);
    }


    private void showNotification()
    {
        // The PendingIntent to launch our activity if the user selects this notification
       PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
       new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.

        Notification notification = new Notification.Builder(this)
            .setSmallIcon(R.mipmap.ic_launcher)   // the status icon
            .setTicker("Monitoring emergencies...")// the status text
            .setWhen(System.currentTimeMillis())    // the time stamp
            .setContentTitle("Too Much Sauce") // the label of the entry
            .setContentText("Currently monitoring any emergency.")   // the contents of the entry
            .setContentIntent(contentIntent)    // The intent to send when the entry is clicked
            .setOngoing(true)       //You can't clear this bitch. Yo understood?!@
            .build();

        // Send the notification.
        mNM.notify(REMINDER_NOTIFICATION, notification);

    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        //Cancel persistent notification.
        mNM.cancel(REMINDER_NOTIFICATION);

        //Cancel sensor shake listener
        if(mSensorManager != null)
            mSensorManager.unregisterListener(mShakeDetector);

        Toast.makeText(this, "Service stopped.", Toast.LENGTH_SHORT).show();
    }

    public void setSpeed(int speed)
    {
        Log.d("SPPPPPEEEEEEEEEDDDDD", "Speed set at " + speed);
        Toast.makeText(this, "Speed set at " + speed, Toast.LENGTH_SHORT).show();
        currentSpeed = speed;
    }

    public int getSpeed()
    {
        Toast.makeText(this, "Speed returned " + currentSpeed, Toast.LENGTH_SHORT).show();
        return currentSpeed;
    }


}
