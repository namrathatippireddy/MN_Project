//  Copyright 2020 VMware, Inc.
//  SPDX-License-Identifier: MIT
//

package com.vmware.herald.sensor.datatype;

import java.util.Date;

/// Payload timestamp, should normally be Date, but it may change to UInt64 in the future to use server synchronised relative timestamp.
public class PayloadTimestamp {
    public final Date value;

    public PayloadTimestamp(Date value) {
        this.value = value;
    }

    public PayloadTimestamp() {
        this(new Date());
    }
}
