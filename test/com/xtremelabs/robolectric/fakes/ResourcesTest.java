package com.xtremelabs.robolectric.fakes;

import android.app.Activity;
import android.app.Application;
import android.content.res.Resources;
import com.xtremelabs.robolectric.DogfoodRobolectricTestRunner;
import com.xtremelabs.robolectric.R;
import com.xtremelabs.robolectric.Robolectric;
import com.xtremelabs.robolectric.res.ResourceLoader;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(DogfoodRobolectricTestRunner.class)
public class ResourcesTest {
    @Test(expected = Resources.NotFoundException.class)
    public void getStringArray_shouldThrowExceptionIfNotFound() throws Exception {
        DogfoodRobolectricTestRunner.addGenericProxies();
        Robolectric.application = ShadowApplication.bind(new Application(), new ResourceLoader(R.class, new File("test/res")));

        new Activity().getResources().getStringArray(-1);
    }
}
