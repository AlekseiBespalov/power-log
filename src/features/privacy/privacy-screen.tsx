import { Link } from 'expo-router';
import { Text, View } from 'react-native';
import { AppShell } from '../../components/app-shell';
import { ExternalLink } from '../../components/external-link';
import { Body, Card, Heading, colors, type, lineHeight, MIN_TOUCH_TARGET } from '../../components/ui';
import { APP_VERSION, ISSUES_URL } from '../../components/app-info';

export function PrivacyScreen() {
  return (
    <AppShell>
      <PrivacyPolicy />
    </AppShell>
  );
}

export function PrivacyPolicy() {
  return (
    <View testID="privacy-screen" style={{ width: '100%', maxWidth: 760, alignSelf: 'center', gap: 16 }}>
      <Link
        href="/settings"
        style={{
          color: colors.accent,
          fontSize: type.body,
          lineHeight: lineHeight.body,
          paddingVertical: 12,
          minHeight: MIN_TOUCH_TARGET,
        }}
      >
        Back to Settings
      </Link>
      <Heading>Privacy policy</Heading>
      <Body muted>Describes Power Log {APP_VERSION}</Body>
      <Body>
        Power Log keeps ride data in the app on your devices. It has no Power Log account, advertising, usage analytics,
        crash-reporting service or ride-upload server.
      </Body>
      <Card>
        <Heading level={2}>What stays on your devices</Heading>
        <Body>
          Power Log stores ride times, pauses, laps, recording choices, bike measurements and calculated summaries. Bike
          measurements include rider power, cadence, speed, torque, battery use, motor readings, temperatures and
          controller status. They include controller model and firmware information, but not the raw controller identity
          serial. Enabled GPS and available Health measurements become part of the local ride.
        </Body>
        <Body>
          iPhone and Android keep recorded rides in a database on the device, readings shown before Start only in
          memory, settings in app files, and temporary imports and exports in app storage. An exported file stays there
          for at least 24 hours so the app you share it with can read it; Power Log deletes it the next time it starts
          or exports after that. iPhone also remembers the selected Bluetooth device and the identifiers, model and
          firmware of controllers it has recognized (disconnecting does not clear that list). iPhone and Android keep a
          bounded connection log of connection events, timings and error codes, without device names, identifiers,
          locations or ride measurements. Apple Watch keeps its recording settings and holds recent readings in memory
          only until your iPhone receives them. The phone keeps the rides; an interrupted ride keeps what was saved
          before the interruption. The iPhone, Apple Watch and Android apps also write error messages to the device’s
          system log.
        </Body>
        <Body>
          On iPhone, Apple Watch and Android, Power Log keeps its rides, settings and logs in private app storage that
          other apps cannot read, protected by the device’s own encryption. Power Log has no server and transmits none
          of this data except as described under What leaves the app.
        </Body>
        <Body>
          Depending on your Apple backup settings, iPhone app data, including rides with their Health readings and
          routes, can be included in device or iCloud backups, even when Health saving is off. Android backup is turned
          off for Power Log. The Android app declares internet access because its app framework requires it; Power Log
          itself sends no ride data over the network.
        </Body>
        <Body>
          The website keeps saved rides in this browser’s IndexedDB and settings in localStorage. CSV files you open are
          read on your device. Exports are prepared in this site’s private browser file storage where the browser offers
          it, and deleted from it the next time the site starts or exports after 24 hours. Browser recording requires a
          visible page. Optional GPS records your route locally; the browser does not use Apple Health or Health
          Connect. Starting a browser recording asks the browser to keep this site’s storage persistent, which reduces
          automatic eviction; clearing site data still removes it. Browser data is not encrypted by Power Log; it is
          accessible to that browser profile and other code allowed to run on the same website origin.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>Bluetooth and location</Heading>
        <Body>
          Bluetooth permission lets iPhone, Android or a compatible browser find and connect to your CYC controller.
          Power Log reads nearby device names and identifiers, then requests controller identity and measurements. It
          cannot change motor settings. Apple Watch receives bike measurements from iPhone.
        </Body>
        <Body>
          Bluetooth diagnostics in Settings is an optional, temporary raw capture of Power Log’s own connection. It can
          include device names, advertisements, services, requests and replies, including controller serial numbers. It
          does not include GPS or Health records. Recording stops after two minutes or at its size limit; the capture
          remains in memory until deleted or the app exits. Export log saves a separate JSON file and opens sharing or a
          browser download. On phones, Clear also removes that local export copy; starting a new capture replaces it.
          Copies you shared or downloaded remain where you saved them.
        </Body>
        <Body>
          Share diagnostics on iPhone and Android puts the connection log, the phone model, system version, power saving
          and Bluetooth permission states, and the app version in a ZIP and opens the system share sheet. Nothing leaves
          the phone unless you share that file.
        </Body>
        <Body>
          Android asks for Nearby devices permission on newer versions. Older Android versions also require location
          permission to scan for Bluetooth devices, even if GPS recording is off.
        </Body>
        <Body>
          With GPS route enabled, the phone uses location for your route, distance, speed and elevation. It stores
          coordinates, times, altitude, direction and accuracy readings. Native recording can continue with the screen
          locked or the app in the background. iPhone requests location access while in use and enables background
          location during recording. Android requires precise location and runs a foreground recording service. The
          website requests browser location only when GPS recording is enabled.
        </Body>
        <Body>
          GPS and Health saving choices apply when you start a ride. Change them before starting the next ride. You can
          revoke permissions in your device or browser settings; that does not delete existing recordings.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>Apple Health</Heading>
        <Body>
          When Apple Health is used, Power Log requests read access to heart rate, heart rate variability, respiratory
          rate, blood oxygen, active and resting energy, physical effort, cycling distance, power, cadence and speed,
          and cycling functional threshold power. Supported systems also provide workout effort and estimated effort
          scores. iPhone also reads workouts to confirm that the Watch saved a Watch-assisted ride. Readings depend on
          your permissions and what the device provides.
        </Body>
        <Body>
          The device running the native workout also requests write access to workouts, routes, heart rate, active and
          resting energy, and cycling distance, power, cadence and speed. Power Log writes nothing else to Apple Health.
          The Watch writes Watch-assisted workouts; iPhone writes other Apple Health workouts. Route permission can be
          requested even when GPS is off; route points are saved only when GPS recording is enabled.
        </Body>
        <Body>
          Watch measurements are transferred to iPhone with their original times. Ride-related Health measurements can
          also be read after Finish or an explicit refresh, retaining their sample identities, times and source details
          locally. The workout session records heart rate and active and resting energy. Power Log adds rider power and
          cadence from the controller and distance, speed and route from phone GPS; motor electrical power is not
          written as rider power.
        </Body>
        <Body>
          A Watch ride still needs Health workout write permission to run its sensor session when Save to Apple Health
          is off. It still requests read access and forwards available sensor readings to the phone, but does not save
          that workout to Apple Health. The native session may still produce system Health samples or Activity credit. A
          ride without Watch assistance and with Health saving off does not need Health access.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>Health Connect on Android</Heading>
        <Body>
          When you turn on Health Connect saving, Power Log asks for permission to write exercise sessions, power, speed
          and distance, and exercise routes when GPS recording is on. It saves each completed ride once as a cycling
          session with its laps, pauses and route, with rider power and cadence from the controller and speed and
          distance from phone GPS. Power Log writes nothing else to Health Connect.
        </Body>
        <Body>
          It also asks to read exercise sessions, heart rate, active calories burned, respiratory rate, oxygen
          saturation, heart rate variability, power, cadence, speed and distance, including records from other apps and
          devices, and to read them in the background. Power Log reads only records from the time of a ride. With Health
          Connect saving on, it reads them at Finish and again 15 and 60 minutes later, in the background; Refresh
          Health data reads them again for any finished ride. Without background access, these reads wait until you open
          Power Log. It skips the records it saved itself. Imported measurements keep their original times. They are
          stored with the ride on your phone, like the rest of its data.
        </Body>
        <Body>
          Power Log does not ask for older Health Connect history, so it does not read rides that started more than 30
          days before you first allowed Health Connect access. Your saved Health copy is separate from the local ride.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>Recording indicators</Heading>
        <Body>
          Android requests notification permission for the recording notification, which shows ride status and a timer
          with pause, resume and finish controls. Its foreground service and wake lock keep native recording running. On
          supported iPhones, a Live Activity can show time, rider power and heart rate on the lock screen. These are
          local updates, not remote push notifications.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>What leaves the app</Heading>
        <Body>
          Starting a Watch ride enables automatic transfers between your Watch and paired iPhone: ride controls, bike
          measurements, Health readings and GPS points. Choosing Health saving lets the app write the permitted ride
          data to Apple Health or Health Connect. Those system services manage their own copies and any sharing or
          synchronization you enable there.
        </Body>
        <Body>
          Export buttons create a FIT file, a CSV file or a ride-data ZIP. On iPhone and Android, sharing opens the
          system share sheet so you choose where the file goes; the website downloads the file. Exports can contain
          precise routes, Health readings and controller details. Imported CSV files can also be exported again.
        </Body>
        <Body>
          Open Strava upload only opens Strava’s website. It sends no ride file. You must select and upload the file
          there yourself; Power Log does not handle Strava accounts or credentials.
        </Body>
        <Body>
          The published website requests its pages and app files from GitHub Pages. GitHub receives those web requests
          and logs visitors’ IP addresses for security. Power Log does not send your local rides, imported CSV files,
          Health readings or GPS points to that host. Opening the source, Issues or website notices links also makes a
          request to the linked website. Information you submit on another website is handled there.
        </Body>
        <ExternalLink href="https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection">
          GitHub Pages data collection
        </ExternalLink>
      </Card>
      <Card>
        <Heading level={2}>Keeping and deleting data</Heading>
        <Body>
          Saved rides stay locally until you delete them or their storage is removed. Use Delete ride in History. The
          Watch does not maintain a second ride archive. Pre-Start previews disappear when the app stops; connection
          logs are bounded separately.
        </Body>
        <Body>
          Deleting a ride does not delete an Apple Health or Health Connect copy, a downloaded or shared file, or
          temporary import and share copies. Manage Health copies in the Health service and exported files where you
          saved them. Device backups and copies held by other apps are outside Power Log’s deletion controls.
        </Body>
        <Body>
          Uninstalling the app removes its local app storage; remove it from both phone and Watch to remove both stores.
          On Android, clearing app data also removes local data. On the web, clear this site’s browser data to remove
          rides and settings. Browsers may clear or evict storage, including when space runs low. Export rides you want
          to keep.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>Children</Heading>
        <Body>
          Power Log does not ask for an age or date of birth and has no separate child profile. The same local recording
          and permission controls apply to anyone using it. Parents or guardians can manage device permissions and
          delete local recordings using the controls above.
        </Body>
      </Card>
      <Card>
        <Heading level={2}>Changes and contact</Heading>
        <Body>
          This page describes the app version shown above. When the app’s data handling changes, the policy can be
          updated with the app and published website. Check the version when reviewing it.
        </Body>
        <Body>
          Use GitHub Issues for questions about this policy. Issues are public: do not attach private rides, routes,
          Health data or device identifiers.
        </Body>
        <ExternalLink href={ISSUES_URL}>GitHub Issues</ExternalLink>
      </Card>
      <Text style={{ color: colors.muted, fontSize: type.caption }}>Power Log {APP_VERSION}</Text>
    </View>
  );
}
