package nisargpatel.inertialnavigation.activity;

import android.annotation.TargetApi;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.dm.zbar.android.scanner.ZBarConstants;

import java.io.IOException;
import java.util.ArrayList;

import nisargpatel.inertialnavigation.R;
import nisargpatel.inertialnavigation.extra.NPExtras;
import nisargpatel.inertialnavigation.filewriting.DataFileWriter;
import nisargpatel.inertialnavigation.graph.ScatterPlot;
import nisargpatel.inertialnavigation.heading.EulerHeadingInference;
import nisargpatel.inertialnavigation.heading.GyroscopeIntegration;
import nisargpatel.inertialnavigation.stepcounting.StaticStepCounter;

public class GraphActivity extends ActionBarActivity implements SensorEventListener{

    private static final double STEP_COUNTER_SENSITIVITY = 1.0;
    private static final double UPPER_THRESHOLD = 11.5;
    private static final double LOWER_THRESHOLD = 6.5;

    private static final String FOLDER_NAME = "Inertial_Navigation_Data/Graph_Activity";
    private static final String[] DATA_FILE_NAMES = {"Accelerometer", "GyroscopeUncalibrated", "XYDataSet"};
    private static final String[] DATA_FILE_HEADINGS = {"dt;Ax;Ay;Az;findStep",
                                                        "dt;Gx;Gy;Gz;heading",
                                                        "dt;strideLength;heading;pointX;pointY"};

    private StaticStepCounter thresholdStepCounter;
    private GyroscopeIntegration gyroscopeIntegration;
    private EulerHeadingInference eulerHeadingInference;
    private DataFileWriter dataFileWriter;
    private ScatterPlot sPlot;

    private LinearLayout linearLayout;

    private Sensor sensorAccelerometer;
    private Sensor sensorGyroscopeUncalibrated;
    private SensorManager sensorManager;

    private float strideLength;

    private boolean filesCreated;

    private float matrixHeading;

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        //getting global settings
        strideLength =  getIntent().getFloatExtra("stride_length", 2.5f);
        String userName = getIntent().getStringExtra("user_name");

        Toast.makeText(GraphActivity.this, "Username: " + userName + "\n" + "Stride Length: " + strideLength, Toast.LENGTH_SHORT).show();

        //defining views
        final Button buttonStart = (Button) findViewById(R.id.buttonGraphStart);
        final Button buttonStop = (Button) findViewById(R.id.buttonGraphStop);
        Button buttonClear = (Button) findViewById(R.id.buttonGraphClear);
        linearLayout = (LinearLayout) findViewById(R.id.linearLayoutGraph);

        //defining sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorGyroscopeUncalibrated = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);

        //initializing needed classes
        thresholdStepCounter = new StaticStepCounter(UPPER_THRESHOLD, LOWER_THRESHOLD);
        gyroscopeIntegration = new GyroscopeIntegration(300, 0.0025f);
        eulerHeadingInference = new EulerHeadingInference(NPExtras.getIdentityMatrix());

        //setting up graph with origin
        sPlot = new ScatterPlot("Position");
        sPlot.addPoint(0, 0);
        linearLayout.addView(sPlot.getGraphView(getApplicationContext()));

        //initializing needed variables
        filesCreated = false;
        matrixHeading = 0;

        //setting up buttons
        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sensorManager.registerListener(GraphActivity.this, sensorAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);
                sensorManager.registerListener(GraphActivity.this, sensorGyroscopeUncalibrated, SensorManager.SENSOR_DELAY_FASTEST);

                Toast.makeText(getApplicationContext(), "Tracking started.", Toast.LENGTH_SHORT).show();

                if (!filesCreated) {
                    try {
                        dataFileWriter = new DataFileWriter(FOLDER_NAME, NPExtras.arrayToList(DATA_FILE_NAMES), NPExtras.arrayToList(DATA_FILE_HEADINGS));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                buttonStart.setEnabled(false);
                buttonStop.setEnabled(true);

            }
        });

        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sensorManager.unregisterListener(GraphActivity.this, sensorAccelerometer);
                sensorManager.unregisterListener(GraphActivity.this, sensorGyroscopeUncalibrated);

                Toast.makeText(getApplicationContext(), "Tracking stopped.", Toast.LENGTH_SHORT).show();

                buttonStart.setEnabled(true);
                buttonStop.setEnabled(false);
            }
        });

        buttonClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eulerHeadingInference.clearMatrix();

                sPlot.clearSet();
                sPlot.addPoint(0,0);
                linearLayout.removeAllViews();
                linearLayout.addView(sPlot.getGraphView(getApplicationContext()));
            }
        });

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            Toast.makeText(getApplicationContext(), data.getStringExtra(ZBarConstants.SCAN_RESULT), Toast.LENGTH_LONG).show();
        } else if (resultCode == RESULT_CANCELED) {
            Toast.makeText(getApplicationContext(), "QR Code Scanner canceled.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {

            float[] deltaOrientation = gyroscopeIntegration.getIntegratedValues(event.timestamp, event.values);
            matrixHeading = eulerHeadingInference.getCurrentHeading(deltaOrientation);

            ArrayList<Float> dataValues = NPExtras.arrayToList(event.values);
            dataValues.add(0, (float) event.timestamp);
            dataValues.add(matrixHeading);

            dataFileWriter.writeToFile("GyroscopeUncalibrated", dataValues);

        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            float zAcc = event.values[2];

            //if step is found, findStep == true
            boolean stepFound = thresholdStepCounter.findStep(zAcc);

            if (stepFound) {

                Log.d("step_counter", "step found");

                ArrayList<Float> dataValues = NPExtras.arrayToList(event.values);
                dataValues.add(0, (float) event.timestamp);
                dataValues.add(1f);
                dataFileWriter.writeToFile("Accelerometer", dataValues);

                //rotation heading output by 90 degrees (pi/2)
                float heading = matrixHeading + (float) (Math.PI / 2.0);
                double pointX = NPExtras.getXFromPolar(strideLength, heading);
                double pointY = NPExtras.getYFromPolar(strideLength, heading);

                pointX += sPlot.getLastXPoint();
                pointY += sPlot.getLastYPoint();
                sPlot.addPoint(pointX, pointY);

                dataValues.clear();
                dataValues.add(strideLength);
                dataValues.add(matrixHeading);
                dataValues.add((float)pointX);
                dataValues.add((float)pointY);

                dataFileWriter.writeToFile("XYDataSet", dataValues);

                linearLayout.removeAllViews();
                linearLayout.addView(sPlot.getGraphView(getApplicationContext()));

                //if step is not found
            } else {
                ArrayList<Float> dataValues = NPExtras.arrayToList(event.values);
                dataValues.add(0, (float) event.timestamp);
                dataValues.add(0f);
                dataFileWriter.writeToFile("Accelerometer", dataValues);
            }

        }
    }

}
