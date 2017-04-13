package edu.uf.mc.sauce.toomuchsos;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.ButtonBarLayout;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.narayanacharya.waveview.WaveView;

public class MainActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener, View.OnClickListener
{
    boolean isBound, isRunning;
    int speed;

    Intent serviceIntent;
    CrashDetectionService crashDetectionService;

    TextView titleLeft, titleRight, speedLabel;
    SeekBar speedSlider;
    Button serviceButton;
    WaveView background;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Typeface tf = Typeface.createFromAsset(getAssets(), "fonts/Sketch.ttf");

        titleLeft = (TextView) findViewById(R.id.titleLeft);
        titleLeft.setTypeface(tf);
        titleRight = (TextView) findViewById(R.id.titleRight);
        titleRight.setTypeface(tf);
        speedLabel = (TextView) findViewById(R.id.currentSpeedIndicator);
        background = (WaveView) findViewById(R.id.waveView);
        int bgColor = Color.rgb(189, 221, 255);
        background.setBackgroundColor(bgColor);
        background.setWaveColor(Color.WHITE);

        speedSlider = (SeekBar) findViewById(R.id.speedBar);
        speedSlider.setOnSeekBarChangeListener(this);
        speedSlider.setMax(140);

        serviceButton = (Button) findViewById(R.id.serviceToggleButton);
        serviceButton.setOnClickListener(this);


        //Real stuff below.

        //TODO: Load speed from service.
        //startService(new Intent(this, CrashDetectionService.class));
        serviceIntent = new Intent(MainActivity.this, CrashDetectionService.class);
        serviceIntent.putExtra("Speed", speed+"");

        isRunning = isServiceRunning();
        if(isRunning)
        {
            //Initialize the button correctly
            serviceButton.setText("STOP SERVICE");

            //Bind the service
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

            //Speed will be set when bindService completes
        }
        else
        {
            //Button is already initialized correctly. nvm.
            serviceButton.setText("START SERVICE");
        }

    }

    public void onTestClick(View v)
    {
        Toast.makeText(this, "Sending b", Toast.LENGTH_SHORT).show();
        if(crashDetectionService != null)
            crashDetectionService.sendMessage("b");
    }

    //Next 3 methods override Seekbar value change.
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean b)
    {
        speed = progress;
        speedLabel.setText(speed + "");
        if(serviceIntent != null)
            serviceIntent.putExtra("Speed", speed+"");

        //TODO: Notify the service
        if(crashDetectionService != null)
        {
            crashDetectionService.setSpeed(speed);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {}


    //Next method handles button press. Only one badass button here!
    @Override
    public void onClick(View view)
    {
        if(serviceButton.getText().equals("START SERVICE"))
        {
            //Start the service
            if(serviceIntent != null)
            {
                //Start the service
                getApplicationContext().startService(serviceIntent);

                //Bind the service
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

                isRunning = true;
            }

            serviceButton.setText("STOP SERVICE");
        }
        else
        {
            //Stop the service
            //idk if this is gonna work
            if(serviceIntent != null)
            {
                //TODO: Unbind.
                getApplicationContext().stopService(serviceIntent);
                isRunning = false;
            }

            serviceButton.setText("START SERVICE");
        }
    }


    @Override
    protected void onStop()
    {
        super.onStop();
        //UnBind from service
        if(isBound)
        {
            unbindService(serviceConnection);
            isBound = false;
        }
    }


    private boolean isServiceRunning()
    {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
        {
            if (CrashDetectionService.class.getName().equals(service.service.getClassName()))
            {
                return true;
            }
        }
        return false;
    }



    private ServiceConnection serviceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder)
        {
            Toast.makeText(MainActivity.this, "Activity bound?", Toast.LENGTH_SHORT).show();
            CrashDetectionService.SOSBinder mBinder = (CrashDetectionService.SOSBinder) iBinder;
            crashDetectionService = mBinder.getService();

            //Set the speed when activity binds to service. (0 initially)
            speed = crashDetectionService.getSpeed();
            speedSlider.setProgress(speed);
            speedLabel.setText(speed + "");

            isBound = true;
            isRunning = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName)
        {
            Toast.makeText(MainActivity.this, "Activity unbound?", Toast.LENGTH_SHORT).show();
            crashDetectionService = null;
            isBound = false;
        }
    };

}
