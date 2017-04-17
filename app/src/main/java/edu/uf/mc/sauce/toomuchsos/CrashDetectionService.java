package edu.uf.mc.sauce.toomuchsos;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.IntegerRes;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by Kaizad on 4/9/2017.
 */
public class CrashDetectionService extends Service implements ShakeDetector.OnShakeListener, GoogleApiClient.ConnectionCallbacks//, BluetoothSPP.AutoConnectionListener, BluetoothSPP.BluetoothConnectionListener, BluetoothSPP.OnDataReceivedListener
{
    private static final int REMINDER_NOTIFICATION = 11;
    private static final int SPEED_ACCIDENT_THRESHOLD = 40; //40 mph honey
    private static final long PIEZO_TIMEOUT_MILLIS = 30000;
    private NotificationManager mNM;

    int currentSpeed;
    boolean isPiezo, isGforce;
    private Handler gTimeoutHandler;

    // The following are used for the shake detection
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private ShakeDetector mShakeDetector;
    private Vibrator vibrator;

    //Following used for Bluetooth serial with Raspberry Pi
    Handler bluetoothIn;
    private BluetoothAdapter btAdapter = null;
    final int handlerState = 0;

    private boolean stopThread;
    // SPP UUID service - this should work for most devices
    private static final UUID BTMODULEUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    // String for MAC address
    //
    //private static final String MAC_ADDRESS = "B8:27:EB:7F:E8:F8";
    private static final String MAC_ADDRESS = "B8:27:EB:C5:3C:11";

    private ConnectingThread mConnectingThread;
    private ConnectedThread mConnectedThread;

    private StringBuilder recDataString = new StringBuilder();
    private GoogleApiClient mGoogleApiClient;
    private boolean locationApi;
    private Runnable gRunnable;

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        locationApi = true;
    }

    @Override
    public void onConnectionSuspended(int i) {
        locationApi = false;
    }

    //Create an inner Binder class
    public class SOSBinder extends Binder {
        public CrashDetectionService getService() {
            return CrashDetectionService.this;  //Return this to access the serice later.
        }
    }

    private final IBinder binder = new SOSBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Toast.makeText(this, "STICKY service started", Toast.LENGTH_SHORT).show();
        if (intent != null && intent.getStringExtra("Speed") != null) {
            Log.d("SSSSSPPPEEEEEDDDDD", currentSpeed + "");
            currentSpeed = Integer.parseInt(intent.getStringExtra("Speed"));
            //Toast.makeText(this, "Started with " + currentSpeed, Toast.LENGTH_SHORT).show();
        }
        super.onStartCommand(intent, flags, startId);
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        showNotification();
        Toast.makeText(this, "Service initialized", Toast.LENGTH_SHORT).show();
        super.onCreate();

        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addApi(LocationServices.API)
                    .build();

            mGoogleApiClient.connect();
        }

        // Everything associated with shake goes below.

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        // ShakeDetector initialization
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mShakeDetector = new ShakeDetector();
        mShakeDetector.setOnShakeListener(this);

        //Register the listener as soon as service starts
        mSensorManager.registerListener(mShakeDetector, mAccelerometer, SensorManager.SENSOR_DELAY_UI);


        bluetoothIn = new Handler() {

            public void handleMessage(android.os.Message msg) {
                Log.d("DEBUG", "handleMessage");
                if (msg.what == handlerState) {
                    String readMessage = (String) msg.obj;
                    recDataString.append(readMessage);
                    Log.d("RECORDED", recDataString.toString());

                    Toast.makeText(CrashDetectionService.this, "Incoming: " + readMessage, Toast.LENGTH_SHORT).show();
                    handleBtInboundMessage(readMessage);
                }
                recDataString.delete(0, recDataString.length());                    //clear all string data
            }
        };

        btAdapter = BluetoothAdapter.getDefaultAdapter();       // get Bluetooth adapter
        checkBTState();

    }

    private void handleBtInboundMessage(String readMessage)
    {
        if (readMessage.equals("ACCIDENT ALERT"))
        {
            isPiezo = true;
            if(currentSpeed < SPEED_ACCIDENT_THRESHOLD)
            {
                //Tell the pi to cancel!
                sendMessage("CANCEL");
                isPiezo = false;
                return; //Homie, won't send an alert
            }
            else if(isGforce && isPiezo)
            {
                sendMessage("ACCIDENT");
                //If speed and gForce and Piezo, send an alert!
                sendAlert(true);
            }
            else
            {
                //Cancel all alerts
                sendMessage("CANCEL");
                isPiezo = false;
            }
        }
        else if (readMessage.equals("CANCEL"))
            sendAlert(false);

    }

    private void checkBTState() {
        if (btAdapter == null) {
            Toast.makeText(this, "NOOO BLUETOOTHHHHH!!!", Toast.LENGTH_LONG).show();
            //stopSelf();
        } else {
            if (btAdapter.isEnabled()) {
                try {
                    BluetoothDevice device = btAdapter.getRemoteDevice(MAC_ADDRESS);
                    mConnectingThread = new ConnectingThread(device);
                    mConnectingThread.start();
                } catch (Exception e) {
                    Toast.makeText(this, "NOOO MAC LIKE THAT!!!", Toast.LENGTH_LONG).show();
                    stopSelf();
                }
            } else {
                Toast.makeText(this, "BLUETOOTHHHHH OFFFF!!!", Toast.LENGTH_LONG).show();
                //stopSelf();
            }
        }
    }

    @Override
    public void onShake(int count)
    {
        Toast.makeText(this, "SHHAAAAAKKKKKKEEEEEEE at speed " + currentSpeed, Toast.LENGTH_LONG).show();
        isGforce = true;
        if(gRunnable != null)
            gTimeoutHandler.removeCallbacks(gRunnable);

        gRunnable = new Runnable() {
            @Override
            public void run() {
                isGforce = false;
                Toast.makeText(CrashDetectionService.this, "GForce " + isGforce, Toast.LENGTH_SHORT).show();
            }
        };
        gTimeoutHandler = new Handler();
        gTimeoutHandler.postDelayed(gRunnable, PIEZO_TIMEOUT_MILLIS);

        //Vibrate for feedback
        if (vibrator.hasVibrator())
            vibrator.vibrate(500);
    }


    private void showNotification() {
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
    public void onDestroy() {
        super.onDestroy();

        //Cancel persistent notification.
        mNM.cancel(REMINDER_NOTIFICATION);

        //Cancel sensor shake listener
        if (mSensorManager != null)
            mSensorManager.unregisterListener(mShakeDetector);

        //Disconnect bt
//        if(bt != null)
//            bt.disconnect();
        bluetoothIn.removeCallbacksAndMessages(null);
        stopThread = true;
        if (mConnectedThread != null) {
            mConnectedThread.closeStreams();
        }
        if (mConnectingThread != null) {
            mConnectingThread.closeSocket();
        }

        //Disconnect Google Location service
        if (mGoogleApiClient != null)
            mGoogleApiClient.disconnect();

        Toast.makeText(this, "Service stopped.", Toast.LENGTH_SHORT).show();
    }

    public void setSpeed(int speed) {
        Log.d("SPPPPPEEEEEEEEEDDDDD", "Speed set at " + speed);
        //Toast.makeText(this, "Speed set at " + speed, Toast.LENGTH_SHORT).show();
        currentSpeed = speed;
    }

    public int getSpeed() {
        //Toast.makeText(this, "Speed returned " + currentSpeed, Toast.LENGTH_SHORT).show();
        return currentSpeed;
    }

    //Sends the SOS message to the server
    private void sendAlert(boolean notCancel) {
        String url = "http://10.136.22.65:9090/everest/incident/";
        if (notCancel)
            url = url + "report";
        else
            url = url + "cancel";

        @SuppressWarnings({"MissingPermission"})
        final Location mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

        // Request a string response
        StringRequest stringRequest = new StringRequest(Request.Method.POST, url,
            new Response.Listener<String>()
            {
                @Override
                public void onResponse(String response)
                {
                    try {
                        JSONObject jsonResponse = new JSONObject(response).getJSONObject("form");
                        String site = jsonResponse.getString("site"),
                                network = jsonResponse.getString("network");
                        System.out.println("Site: "+site+"\nNetwork: "+network);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            },
            new Response.ErrorListener()
            {
                @Override
                public void onErrorResponse(VolleyError error)
                {
                    error.printStackTrace();
                }
            }
        ) {
            @Override
            protected Map<String, String> getParams()
            {

                String latitude = "29.648761";  //Default CISE :P
                String longitude = "-82.344195";

                if (mLastLocation != null)
                {
                    latitude = (String.valueOf(mLastLocation.getLatitude()));
                    longitude = (String.valueOf(mLastLocation.getLongitude()));
                }
                Map<String, String> params = new HashMap<>(

                );
                // the POST parameters:

                params.put("uuid", BTMODULEUUID.toString());
                params.put("lat", latitude);
                params.put("lng", longitude);
                return params;
            }
        };


        // Add the request to the queue
        Volley.newRequestQueue(this).add(stringRequest);
    }

    public void sendMessage(String msg)
    {
        if(mConnectedThread != null)
            mConnectedThread.write(msg);
        //sendAlert(true);  //This was for testing on the button.
    }

    // New Class for Connecting Thread
    private class ConnectingThread extends Thread
    {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectingThread(BluetoothDevice device) {
            Log.d("DEBUG BT", "IN CONNECTING THREAD");
            mmDevice = device;
            BluetoothSocket temp = null;
            Log.d("DEBUG BT", "MAC ADDRESS : " + MAC_ADDRESS);
            Log.d("DEBUG BT", "BT UUID : " + BTMODULEUUID);
            try {
                temp = mmDevice.createRfcommSocketToServiceRecord(BTMODULEUUID);
                Log.d("DEBUG BT", "SOCKET CREATED : " + temp.toString());
            } catch (IOException e) {
                Log.d("DEBUG BT", "SOCKET CREATION FAILED :" + e.toString());
                Log.d("BT SERVICE", "SOCKET CREATION FAILED, STOPPING SERVICE");
                //stopSelf();
            }
            mmSocket = temp;
        }

        @Override
        public void run() {
            super.run();
            Log.d("DEBUG BT", "IN CONNECTING THREAD RUN");
            // Establish the Bluetooth socket connection.
            // Cancelling discovery as it may slow down connection
            btAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
                Log.d("DEBUG BT", "BT SOCKET CONNECTED");
                mConnectedThread = new ConnectedThread(mmSocket);
                mConnectedThread.start();
                Log.d("DEBUG BT", "CONNECTED THREAD STARTED");
                //I send a character when resuming.beginning transmission to check device is connected
                //If it is not an exception will be thrown in the write method and finish() will be called
                //mConnectedThread.write("x");
            } catch (IOException e) {
                try {
                    Log.d("DEBUG BT", "SOCKET CONNECTION FAILED : " + e.toString());
                    Log.d("BT SERVICE", "SOCKET CONNECTION FAILED, STOPPING SERVICE");
                    mmSocket.close();
                    //stopSelf();
                } catch (IOException e2) {
                    Log.d("DEBUG BT", "SOCKET CLOSING FAILED :" + e2.toString());
                    Log.d("BT SERVICE", "SOCKET CLOSING FAILED, STOPPING SERVICE");
                    //stopSelf();
                    //insert code to deal with this
                }
            } catch (IllegalStateException e) {
                Log.d("DEBUG BT", "CONNECTED THREAD START FAILED : " + e.toString());
                Log.d("BT SERVICE", "CONNECTED THREAD START FAILED, STOPPING SERVICE");
                //stopSelf();
            }
        }

        public void closeSocket() {
            try {
                //Don't leave Bluetooth sockets open when leaving activity
                mmSocket.close();
            } catch (IOException e2) {
                //insert code to deal with this
                Log.d("DEBUG BT", e2.toString());
                Log.d("BT SERVICE", "SOCKET CLOSING FAILED, STOPPING SERVICE");
                //stopSelf();
            }
        }
    }

    // New Class for Connected Thread
    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        //creation of the connect thread
        public ConnectedThread(BluetoothSocket socket) {
            Log.d("DEBUG BT", "IN CONNECTED THREAD");
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                //Create I/O streams for connection
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.d("DEBUG BT", e.toString());
                Log.d("BT SERVICE", "UNABLE TO READ/WRITE, STOPPING SERVICE");
                //stopSelf();
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.d("DEBUG BT", "IN CONNECTED THREAD RUN");
            byte[] buffer = new byte[256];
            int bytes;

            // Keep looping to listen for received messages
            while (true && !stopThread) {
                try {
                    bytes = mmInStream.read(buffer);            //read bytes from input buffer
                    String readMessage = new String(buffer, 0, bytes);
                    Log.d("DEBUG BT PART", "CONNECTED THREAD " + readMessage);
                    // Send the obtained bytes to the UI Activity via handler
                    bluetoothIn.obtainMessage(handlerState, bytes, -1, readMessage).sendToTarget();
                } catch (IOException e) {
                    Log.d("DEBUG BT", e.toString());
                    Log.d("BT SERVICE", "UNABLE TO READ/WRITE, STOPPING SERVICE");
                    //stopSelf();
                    break;
                }
            }
        }

        //write method
        public void write(String input) {
            byte[] msgBuffer = input.getBytes();           //converts entered String into bytes
            try {
                mmOutStream.write(msgBuffer);                //write bytes over BT connection via outstream
            } catch (IOException e) {
                //if you cannot write, close the application
                Log.d("DEBUG BT", "UNABLE TO READ/WRITE " + e.toString());
                Log.d("BT SERVICE", "UNABLE TO READ/WRITE, STOPPING SERVICE");
                Toast.makeText(CrashDetectionService.this, "Failed to write BT message: " + input, Toast.LENGTH_SHORT).show();
                //stopSelf();
            }
        }

        public void closeStreams() {
            try {
                //Don't leave Bluetooth sockets open when leaving activity
                mmInStream.close();
                mmOutStream.close();
            } catch (IOException e2) {
                //insert code to deal with this
                Log.d("DEBUG BT", e2.toString());
                Log.d("BT SERVICE", "STREAM CLOSING FAILED, STOPPING SERVICE");
                //stopSelf();
            }
        }
    }

}
