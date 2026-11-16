package com.clawmate.platform.display;

import android.content.Intent;
import android.view.Surface;

interface IDisplayHost {
    int open(in Intent target);
    oneway void close(int displayId);
    oneway void setPreviewSurface(int displayId, in @nullable Surface surface);
}
