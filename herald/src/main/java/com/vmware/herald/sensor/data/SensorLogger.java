//  Copyright 2020 VMware, Inc.
//  SPDX-License-Identifier: MIT
//

package com.vmware.herald.sensor.data;

public interface SensorLogger {

    void debug(String message, final Object... values);

    void info(String message, final Object... values);

    void fault(String message, final Object... values);
}
