package com.jonkryl.packinglist;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.jonkryl.packinglist.domain.PackingModels.Trip;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.intent.Intents.*;
import static androidx.test.espresso.intent.matcher.IntentMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static com.jonkryl.packinglist.JourneySupport.*;

/** Invoked in a fresh application process by device-journey.sh after force-stop. */
@RunWith(AndroidJUnit4.class)
public final class RestartAndExportTest {
    @Test public void savedTripSurvivesProcessRestartAndSystemShareContainsTheRealList() throws Exception {
        locale("en");
        assertOffline();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Trip trip = trip(scenario);
            assertEquals(TITLE, trip.title);
            assertEquals("2026-10-10", trip.startDate);
            assertEquals(3, trip.items.size());
            assertEquals(1, trip.packedCount());
            assertEquals("Bottle", trip.items.get(0).name);
            assertEquals(3, trip.items.get(0).quantity);
            assertEquals("Ada Rivera", trip.personName(trip.items.get(0).personId));
            assertEquals("Cabin bag", trip.bagName(trip.items.get(0).bagId));
            assertEquals("Bottle", trip.items.get(1).name);
            assertEquals("Boris", trip.personName(trip.items.get(1).personId));
            screenshot("06-en-after-process-restart");
            Intents.init();
            try {
                intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(new ActivityResult(Activity.RESULT_OK, null));
                clickMainId("share_button");
                intended(new TypeSafeMatcher<Intent>() {
                    @Override public void describeTo(Description description) {
                        description.appendText("system share chooser with the persisted packing list");
                    }
                    @Override protected boolean matchesSafely(Intent intent) {
                        if (!Intent.ACTION_CHOOSER.equals(intent.getAction())) return false;
                        Intent send = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (send == null || !Intent.ACTION_SEND.equals(send.getAction()) || !"text/plain".equals(send.getType())) return false;
                        String content = send.getStringExtra(Intent.EXTRA_TEXT);
                        return content != null && content.contains(TITLE) && content.contains("Bottle")
                                && content.contains("Ada Rivera") && content.contains("Cabin bag")
                                && content.contains("Boris") && content.contains("Passport");
                    }
                });
            } finally { Intents.release(); }
        }
    }
}
