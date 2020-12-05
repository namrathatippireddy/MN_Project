//  Copyright 2020 VMware, Inc.
//  SPDX-License-Identifier: MIT
//

package com.vmware.herald.app;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.vmware.herald.sensor.Sensor;
import com.vmware.herald.sensor.SensorArray;
import com.vmware.herald.sensor.SensorDelegate;
import com.vmware.herald.sensor.analysis.SocialDistance;
import com.vmware.herald.sensor.datatype.ImmediateSendData;
import com.vmware.herald.sensor.datatype.Location;
import com.vmware.herald.sensor.datatype.PayloadData;
import com.vmware.herald.sensor.datatype.Proximity;
import com.vmware.herald.sensor.datatype.SensorState;
import com.vmware.herald.sensor.datatype.SensorType;
import com.vmware.herald.sensor.datatype.TargetIdentifier;
import com.vmware.herald.sensor.datatype.TimeInterval;

import org.w3c.dom.Text;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class MainActivity extends AppCompatActivity implements SensorDelegate, AdapterView.OnItemClickListener {
    private final static String tag = MainActivity.class.getName();
    /// REQUIRED: Unique permission request code, used by requestPermission and onRequestPermissionsResult.
    private final static int permissionRequestCode = 1249951875;
    /// Test UI specific data, not required for production solution.
    private final static SimpleDateFormat dateFormatter = new SimpleDateFormat("YYYY-MM-dd HH:mm:ss");

    // MARK:- Events
    private long didDetect = 0, didRead = 0, didMeasure = 0, didShare = 0, didReceive = 0;

    // MARK:- Detected payloads
    private final Map<TargetIdentifier,PayloadData> targetIdentifiers = new ConcurrentHashMap<>();
    private final Map<PayloadData,Target> payloads = new ConcurrentHashMap<>();
    private final List<Target> targets = new ArrayList<>();
    private TargetListAdapter targetListAdapter = null;

    // MARK:- Social mixing
    private final SocialDistance socialMixingScore = new SocialDistance();
    private TimeInterval socialMixingScoreUnit = new TimeInterval(60);

    LinearLayout scanQRButton;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setContentView(R.layout.activity_main);

        scanQRButton= (LinearLayout) findViewById(R.id.scanButton);

        scanQRButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scanCode();
            }
        });

        // REQUIRED : Ensure app has all required permissions
        requestPermissions();

        // Test UI specific process to gather data from sensor for presentation
        final Sensor sensor = AppDelegate.getAppDelegate().sensor();
        sensor.add(this);
        sensor.add(socialMixingScore);
        ((TextView) findViewById(R.id.device)).setText(SensorArray.deviceDescription);
        ((TextView) findViewById(R.id.payload)).setText("KEY : " + ((SensorArray) AppDelegate.getAppDelegate().sensor()).payloadData().shortName());


        targetListAdapter = new TargetListAdapter(this, targets);
        final ListView targetsListView = ((ListView) findViewById(R.id.targets));
        targetsListView.setAdapter(targetListAdapter);
        targetsListView.setOnItemClickListener(this);



    }


    public void scanCode(){
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setCaptureActivity(CaptureAct.class);
        integrator.setOrientationLocked(false);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
        integrator.setPrompt("Scanning Code");
        integrator.initiateScan();
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        IntentResult result= IntentIntegrator.parseActivityResult(requestCode,resultCode,data);
        if(result!=null){
            if(result.getContents()!=null){
                AlertDialog.Builder builder=new AlertDialog.Builder(this);
                builder.setMessage(result.getContents());
                builder.setTitle("Scanning Result");
                builder.setPositiveButton("Scan Again", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        scanCode();
                    }
                }).setNegativeButton("finish", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {

                    }
                });

                AlertDialog dialog=builder.create();
                dialog.show();
            }else {
                Toast.makeText(this,"No results",Toast.LENGTH_LONG).show();
            }
        }else{
            super.onActivityResult(requestCode,resultCode,data);
        }
    }

    /// REQUIRED : Request application permissions for sensor operation.
    private void requestPermissions() {
        // Check and request permissions
        final List<String> requiredPermissions = new ArrayList<>();
        requiredPermissions.add(Manifest.permission.BLUETOOTH);
        requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        requiredPermissions.add(Manifest.permission.WAKE_LOCK);
        final String[] requiredPermissionsArray = requiredPermissions.toArray(new String[0]);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(requiredPermissionsArray, permissionRequestCode);
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissionsArray, permissionRequestCode);
        }
    }

    /// REQUIRED : Handle permission results.
    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == permissionRequestCode) {
            boolean permissionsGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                final String permission = permissions[i];
                if (grantResults[i] != PERMISSION_GRANTED) {
                    Log.e(tag, "Permission denied (permission=" + permission + ")");
                    permissionsGranted = false;
                } else {
                    Log.d(tag, "Permission granted (permission=" + permission + ")");
                }
            }

            if (!permissionsGranted) {
                Log.e(tag, "Application does not have all required permissions to start (permissions=" + Arrays.asList(permissions) + ")");
            }
        }
    }

    // MARK:- Test UI specific functions, not required in production solution.

    // Update targets table
    private void updateTargets() {
        final List<Target> targetList = new ArrayList<>(payloads.values());
        Collections.sort(targetList, new Comparator<Target>() {
            @Override
            public int compare(Target t0, Target t1) {
                return t0.payloadData().shortName().compareTo(t1.payloadData().shortName());
            }
        });
        ((TextView) findViewById(R.id.detection)).setText("DETECTION (" + targetListAdapter.getCount() + ")");
        targetListAdapter.clear();
        targetListAdapter.addAll(targetList);
    }
//
//    // Update social distance score
//    private void updateSocialDistance(TimeInterval unit) {
//        final long millisecondsPerUnit = unit.value * 1000;
//        final List<TextView> labels = new ArrayList<>();
//        labels.add((TextView) findViewById(R.id.socialMixingScore00));
//        labels.add((TextView) findViewById(R.id.socialMixingScore01));
//        labels.add((TextView) findViewById(R.id.socialMixingScore02));
//        labels.add((TextView) findViewById(R.id.socialMixingScore03));
//        labels.add((TextView) findViewById(R.id.socialMixingScore04));
//        labels.add((TextView) findViewById(R.id.socialMixingScore05));
//        labels.add((TextView) findViewById(R.id.socialMixingScore06));
//        labels.add((TextView) findViewById(R.id.socialMixingScore07));
//        labels.add((TextView) findViewById(R.id.socialMixingScore08));
//        labels.add((TextView) findViewById(R.id.socialMixingScore09));
//        labels.add((TextView) findViewById(R.id.socialMixingScore10));
//        labels.add((TextView) findViewById(R.id.socialMixingScore11));
//        final long epoch = (new Date().getTime() / millisecondsPerUnit) - 11;
//        for (int i=0; i<=11; i++) {
//            // Compute score for time slot
//            final Date start = new Date((epoch + i) * millisecondsPerUnit);
//            final Date end = new Date((epoch + i + 1) * millisecondsPerUnit);
//            final double score = socialMixingScore.scoreByProximity(start, end, -25, -70);
//            // Present textual score
//            final String scoreForPresentation = Integer.toString((int) Math.round(score * 100));
//            labels.get(i).setText(scoreForPresentation);
//            // Change color according to score
//            if (score < 0.1) {
//                labels.get(i).setBackgroundColor(ContextCompat.getColor(this, R.color.systemGreen));
//            } else if (score < 0.5) {
//                labels.get(i).setBackgroundColor(ContextCompat.getColor(this, R.color.systemOrange));
//            } else {
//                labels.get(i).setBackgroundColor(ContextCompat.getColor(this, R.color.systemRed));
//            }
//        }
//    }
//
//    public void onClickSocialMixingScoreUnit(View v) {
//        final Map<TextView, TimeInterval> mapping = new HashMap<>(12);
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitH24), new TimeInterval(24 * 60 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitH12), new TimeInterval(12 * 60 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitH4), new TimeInterval(4 * 60 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitH1), new TimeInterval(1 * 60 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitM30), new TimeInterval(30 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitM15), new TimeInterval(15 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitM5), new TimeInterval(5 * 60));
//        mapping.put((TextView) findViewById(R.id.socialMixingScoreUnitM1), new TimeInterval(1 * 60));
//        final int active = ContextCompat.getColor(this, R.color.systemBlue);
//        final int inactive = ContextCompat.getColor(this, R.color.systemGray);
//        final TextView setTo = (TextView) v;
//        for (TextView key : mapping.keySet()) {
//            if (setTo.getId() == key.getId()) {
//                key.setTextColor(active);
//                socialMixingScoreUnit = mapping.get(key);
//            } else {
//                key.setTextColor(inactive);
//            }
//        }
//        updateSocialDistance(socialMixingScoreUnit);
//    }

    // MARK:- SensorDelegate

    @Override
    public void sensor(SensorType sensor, TargetIdentifier didDetect) {
        this.didDetect++;
        final String text = Long.toString(this.didDetect);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final TextView textView = findViewById(R.id.didDetectCount);
                textView.setText(text);
            }
        });
    }

    @Override
    public void sensor(SensorType sensor, PayloadData didRead, TargetIdentifier fromTarget) {
//        this.didRead++;
//        targetIdentifiers.put(fromTarget, didRead);
//        Target target = payloads.get(didRead);
//        if (target != null) {
//            target.didRead(new Date());
//        } else {
//            payloads.put(didRead, new Target(fromTarget, didRead));
//        }
//        final String text = Long.toString(this.didRead);
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                final TextView textView = findViewById(R.id.didReadCount);
//                textView.setText(text);
//                updateTargets();
//            }
//        });
    }

    @Override
    public void sensor(SensorType sensor, List<PayloadData> didShare, TargetIdentifier fromTarget) {
//        this.didShare++;
//        final Date now = new Date();
//        for (PayloadData didRead : didShare) {
//            targetIdentifiers.put(fromTarget, didRead);
//            Target target = payloads.get(didRead);
//            if (target != null) {
//                target.didRead(new Date());
//            } else {
//                payloads.put(didRead, new Target(fromTarget, didRead));
//            }
//        }
//        final String text = Long.toString(this.didShare);
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                final TextView textView = findViewById(R.id.didShareCount);
//                textView.setText(text);
//                updateTargets();
//            }
//        });
    }

    @Override
    public void sensor(SensorType sensor, Proximity didMeasure, TargetIdentifier fromTarget) {
//        this.didMeasure++;
//        final PayloadData didRead = targetIdentifiers.get(fromTarget);
//        if (didRead != null) {
//            final Target target = payloads.get(didRead);
//            if (target != null) {
//                target.targetIdentifier(fromTarget);
//                target.proximity(didMeasure);
//            }
//        }
//        final String text = Long.toString(this.didMeasure);
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                final TextView textView = findViewById(R.id.didMeasureCount);
//                textView.setText(text);
//                updateTargets();
//            }
//        });
    }

    @Override
    public void sensor(SensorType sensor, ImmediateSendData didReceive, TargetIdentifier fromTarget) {
//        this.didReceive++;
//        final PayloadData didRead = new PayloadData(didReceive.data.value);
//        if (didRead != null) {
//            final Target target = payloads.get(didRead);
//            if (target != null) {
//                targetIdentifiers.put(fromTarget, didRead);
//                target.targetIdentifier(fromTarget);
//                target.received(didReceive);
//            }
//        }
//        final String text = Long.toString(this.didReceive);
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                final TextView textView = findViewById(R.id.didReceiveCount);
//                textView.setText(text);
//                updateTargets();
//            }
//        });
    }

    @Override
    public void sensor(SensorType sensor, Location didVisit) {
        // Not used
    }

    @Override
    public void sensor(SensorType sensor, Proximity didMeasure, TargetIdentifier fromTarget, PayloadData withPayload) {
        // High level integration API is not used as the test app is using the low level API to present all the detection events.
    }

    @Override
    public void sensor(SensorType sensor, SensorState didUpdateState) {
        // Sensor state is already presented by the operating system, so not duplicating in the test app.
    }

    // MARK:- OnItemClickListener

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
        final Target target = targetListAdapter.getItem(i);
        final SensorArray sensor = (SensorArray) AppDelegate.getAppDelegate().sensor();
        final PayloadData payloadData = sensor.payloadData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean result = sensor.immediateSend(payloadData, target.targetIdentifier());
                Log.d(tag, "immediateSend (to=" + target.payloadData().shortName() + ",result=" + result + ")");
            }
        }).start();
    }
}