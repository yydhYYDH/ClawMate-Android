package com.clawmate.platform.display;

import android.content.Intent;

interface IDisplayHost {
    int open(in Intent target);
    oneway void close(int displayId);
}
