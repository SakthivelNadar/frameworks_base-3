/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.collection.coordinator;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.util.Assert;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ForegroundCoordinatorTest extends SysuiTestCase {
    private static final String TEST_PKG = "test_pkg";
    private static final int NOTIF_USER_ID = 0;

    @Mock private ForegroundServiceController mForegroundServiceController;
    @Mock private AppOpsController mAppOpsController;
    @Mock private NotifPipeline mNotifPipeline;

    @Captor private ArgumentCaptor<AppOpsController.Callback> mAppOpsCaptor;

    private NotificationEntry mEntry;
    private Notification mNotification;
    private ForegroundCoordinator mForegroundCoordinator;
    private NotifFilter mForegroundFilter;
    private AppOpsController.Callback mAppOpsCallback;
    private NotifLifetimeExtender mForegroundNotifLifetimeExtender;

    private FakeSystemClock mClock = new FakeSystemClock();
    private FakeExecutor mExecutor = new FakeExecutor(mClock);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        Assert.sMainLooper = TestableLooper.get(this).getLooper();

        mForegroundCoordinator =
                new ForegroundCoordinator(
                        mForegroundServiceController,
                        mAppOpsController,
                        mExecutor);

        mNotification = new Notification();
        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setNotification(mNotification)
                .build();

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        ArgumentCaptor<NotifLifetimeExtender> lifetimeExtenderCaptor =
                ArgumentCaptor.forClass(NotifLifetimeExtender.class);

        mForegroundCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(1)).addPreGroupFilter(filterCaptor.capture());
        verify(mNotifPipeline, times(1)).addNotificationLifetimeExtender(
                lifetimeExtenderCaptor.capture());
        verify(mAppOpsController).addCallback(any(int[].class), mAppOpsCaptor.capture());

        mForegroundFilter = filterCaptor.getValue();
        mForegroundNotifLifetimeExtender = lifetimeExtenderCaptor.getValue();
        mAppOpsCallback = mAppOpsCaptor.getValue();
    }

    @Test
    public void filterTest_disclosureUnnecessary() {
        StatusBarNotification sbn = mEntry.getSbn();

        // GIVEN the notification is a disclosure notification
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(true);

        // GIVEN the disclosure isn't needed for this user
        when(mForegroundServiceController.isDisclosureNeededForUser(sbn.getUserId()))
                .thenReturn(false);

        // THEN filter out the notification
        assertTrue(mForegroundFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterTest_systemAlertNotificationUnnecessary() {
        StatusBarNotification sbn = mEntry.getSbn();

        // GIVEN the notification is a system alert notification + not a disclosure notification
        when(mForegroundServiceController.isSystemAlertNotification(sbn)).thenReturn(true);
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(false);

        // GIVEN the alert notification isn't needed for this user
        final Bundle extras = new Bundle();
        extras.putStringArray(Notification.EXTRA_FOREGROUND_APPS,
                new String[]{TEST_PKG});
        mNotification.extras = extras;
        when(mForegroundServiceController.isSystemAlertWarningNeeded(sbn.getUserId(), TEST_PKG))
                .thenReturn(false);

        // THEN filter out the notification
        assertTrue(mForegroundFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterTest_doNotFilter() {
        StatusBarNotification sbn = mEntry.getSbn();

        // GIVEN the notification isn't a system alert notification nor a disclosure notification
        when(mForegroundServiceController.isSystemAlertNotification(sbn)).thenReturn(false);
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(false);

        // THEN don't filter out the notification
        assertFalse(mForegroundFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void extendLifetimeText_notForeground() {
        // GIVEN the notification doesn't represent a foreground service
        mNotification.flags = 0;

        // THEN don't extend the lifetime
        assertFalse(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void extendLifetimeText_foregroundNotifRecentlyPosted() {
        // GIVEN the notification represents a foreground service that was just posted
        mNotification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setSbn(new StatusBarNotification(TEST_PKG, TEST_PKG, NOTIF_USER_ID, "",
                        NOTIF_USER_ID, NOTIF_USER_ID, mNotification,
                        new UserHandle(NOTIF_USER_ID), "", System.currentTimeMillis()))
                .setNotification(mNotification)
                .build();

        // THEN extend the lifetime
        assertTrue(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void extendLifetimeText_foregroundNotifOld() {
        // GIVEN the notification represents a foreground service that was posted 10 seconds ago
        mNotification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setSbn(new StatusBarNotification(TEST_PKG, TEST_PKG, NOTIF_USER_ID, "",
                        NOTIF_USER_ID, NOTIF_USER_ID, mNotification,
                        new UserHandle(NOTIF_USER_ID), "",
                        System.currentTimeMillis() - 10000))
                .setNotification(mNotification)
                .build();

        // THEN don't extend the lifetime because the extended time exceeds
        // ForegroundCoordinator.MIN_FGS_TIME_MS
        assertFalse(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void testAppOpsAreApplied() {
        // GIVEN Three current notifications, two with the same key but from different users
        NotificationEntry entry1 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(1)
                .build();
        NotificationEntry entry2 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        NotificationEntry entry2Other = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID + 1))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        when(mNotifPipeline.getActiveNotifs()).thenReturn(List.of(entry1, entry2, entry2Other));

        // GIVEN that entry2 is currently associated with a foreground service
        when(mForegroundServiceController.getStandardLayoutKey(0, TEST_PKG))
                .thenReturn(entry2.getKey());

        // WHEN a new app ops code comes in
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, true);
        mExecutor.runAllReady();

        // THEN entry2's app ops are updated, but no one else's are
        assertEquals(
                new ArraySet<>(),
                entry1.mActiveAppOps);
        assertEquals(
                new ArraySet<>(List.of(47)),
                entry2.mActiveAppOps);
        assertEquals(
                new ArraySet<>(),
                entry2Other.mActiveAppOps);
    }

    @Test
    public void testAppOpsAreRemoved() {
        // GIVEN One notification which is associated with app ops
        NotificationEntry entry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        when(mNotifPipeline.getActiveNotifs()).thenReturn(List.of(entry));
        when(mForegroundServiceController.getStandardLayoutKey(0, TEST_PKG))
                .thenReturn(entry.getKey());

        // GIVEN that the notification's app ops are already [47, 33]
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, true);
        mAppOpsCallback.onActiveStateChanged(33, NOTIF_USER_ID, TEST_PKG, true);
        mExecutor.runAllReady();
        assertEquals(
                new ArraySet<>(List.of(47, 33)),
                entry.mActiveAppOps);

        // WHEN one of the app ops is removed
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, false);
        mExecutor.runAllReady();

        // THEN the entry's active app ops are updated as well
        assertEquals(
                new ArraySet<>(List.of(33)),
                entry.mActiveAppOps);
    }
}
