//  Copyright 2020 VMware, Inc.
//  SPDX-License-Identifier: MIT
//

package com.vmware.herald.MN;

import com.vmware.herald.sensor.datatype.ImmediateSendData;
import com.vmware.herald.sensor.datatype.PayloadData;
import com.vmware.herald.sensor.datatype.Proximity;
import com.vmware.herald.sensor.datatype.TargetIdentifier;

import java.util.Date;

public class Target {
    private TargetIdentifier targetIdentifier = null;
    private PayloadData payloadData = null;
    private Date lastUpdatedAt = null;
    private Proximity proximity = null;
    private ImmediateSendData received = null;
    private Date didRead = null, didMeasure = null, didShare = null, didReceive = null;

    public Target(TargetIdentifier targetIdentifier, PayloadData payloadData) {
        this.targetIdentifier = targetIdentifier;
        this.payloadData = payloadData;
        lastUpdatedAt = new Date();
        didRead = lastUpdatedAt;
    }

    public TargetIdentifier targetIdentifier() {
        return targetIdentifier;
    }

    public void targetIdentifier(TargetIdentifier targetIdentifier) {
        lastUpdatedAt = new Date();
        this.targetIdentifier = targetIdentifier;
    }

    public PayloadData payloadData() {
        return payloadData;
    }

    public Date lastUpdatedAt() {
        return lastUpdatedAt;
    }

    public Proximity proximity() {
        return proximity;
    }

    public void proximity(Proximity proximity) {
        lastUpdatedAt = new Date();
        didMeasure = lastUpdatedAt;
        this.proximity = proximity;
    }

    public ImmediateSendData received() {
        return received;
    }

    public void received(ImmediateSendData received) {
        lastUpdatedAt = new Date();
        didReceive = lastUpdatedAt;
        this.received = received;
    }

    public Date didReceive() {
        return didReceive;
    }

    public Date didRead() {
        return didRead;
    }

    public void didRead(Date date) {
        didRead = date;
        lastUpdatedAt = didRead;
    }

    public Date didMeasure() {
        return didMeasure;
    }

    public Date didShare() {
        return didShare;
    }

    public void didShare(Date date) {
        didShare = date;
        lastUpdatedAt = didShare;
    }
}
