package com.clawmate.shared;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** SDK-only disposable fixture application; no host/Kotlin/runtime dependency. */
public class GuiControlFixtureActivity extends Activity {
    private int count;
    private ScrollView scroll;
    private void describe() { scroll.setContentDescription("Fixture scroll offset: " + scroll.getScrollY() + ", count: " + count); }
    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(16,40,16,40);
        TextView title=new TextView(this);title.setText("ClawMate GUI Fixture");title.setTextSize(24);column.addView(title);
        TextView counter=new TextView(this);counter.setText("Count: 0");counter.setTextSize(24);column.addView(counter);
        scroll=new ScrollView(this);
        Button increment=new Button(this);increment.setText("Increment fixture");increment.setContentDescription("Increment fixture");increment.setOnClickListener(v->{count++;counter.setText("Count: "+count);describe();});column.addView(increment);
        for(int i=0;i<40;i++){TextView row=new TextView(this);row.setText("Fixture row "+i);row.setTextSize(22);row.setPadding(0,12,0,12);column.addView(row);}
        scroll.addView(column);scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->describe());describe();setContentView(scroll);
    }
}
