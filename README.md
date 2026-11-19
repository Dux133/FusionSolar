# FusionSolar Monitor — Xiaomi MiProjLED1 (Android 6.0.1)

Lekka natywna aplikacja (Java, bez WebView) wyświetla dane paneli fotowoltaicznych z Huawei FusionSolar Kiosk. Dane podawane są przez udostępniony w FusionSolar adres Kiosk. Nie wymaga logowania do konta Huawei, ale każdy posiadacz URL Kiosk może odczytać udostępnione dane — traktuj ten adres jak sekret.

## Co pokazuje
- Moc produkcji (`realTimePower`), kW.
- Energię dzienną (`dailyEnergy`), miesięczną (`monthEnergy`), roczną (`yearEnergy`) i całkowitą (`cumulativeEnergy`), kWh lub automatycznie MWh.
- Status połączenia i czas ostatniego pobrania.
- Przetrzymuje poprzednie wartości w pamięci przy przerwie internetu; sygnalizuje nieaktualne dane.

**UWAGA**: Dane Kiosk są zwykle aktualizowane w chmurze FusionSolar co około 30 minut. Aplikacja odpytuje endpoint co 10 minut, ale to nie jest pomiar na żywo. Bez dodatkowego licznika nie wyświetla rzeczywistego zużycia domu ani eksportu do sieci.

## Kompilacja przez GitHub Actions

1. Utwórz **prywatne** repozytorium np. `FusionSolar-Monitor`.
2. Wgraj zawartość folderu projektu **zachowując podfoldery**, w tym ukryty `.github/workflows/build-apk.yml`.
3. Wejdź do Actions → `Zbuduj FusionSolar Monitor APK` → Run workflow.
4. Po zielonym wyniku pobierz artifact `FusionSolar_Monitor_Xiaomi_APK` i rozpakuj plik `FusionSolar_Monitor_Xiaomi.apk`.
5. Nie umieszczaj prywatnego URL Kiosk w plikach repozytorium.

## Instalacja przez ADB (Windows PowerShell)

W katalogu z `adb.exe`:

```powershell
.\adb.exe connect 192.168.1.12:5555
.\adb.exe install -r "C:\Users\Dominik\Downloads\FusionSolar_Monitor_Xiaomi.apk"
```

Jeśli zmienił się podpis debug APK i pojawia się `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, odinstaluj poprzednią aplikację `pl.fusionsolar.monitor` (utracisz jej ustawienia) i zainstaluj ponownie.

## Jak ustawić prywatny URL Kiosk — bez przesyłania go na GitHub

Najwygodniej z Windows PowerShell w katalogu `platform-tools`, gdy projektor jest połączony przez ADB:

```powershell
$url = Read-Host 'Wklej pełny URL Kiosk FusionSolar'
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($url))
.\adb.exe shell am start -n pl.fusionsolar.monitor/.MainActivity --es kiosk_url_b64 $b64
Remove-Variable url,b64
```

Base64 służy wyłącznie do bezproblemowego przekazania znaków `?`, `#`, `&` przez ADB; **nie jest szyfrowaniem**. Aplikacja sprawdza, czy URL jest HTTPS w domenie `fusionsolar.huawei.com` i ma identyfikator `kk`. URL zapisuje lokalnie w prywatnych ustawieniach aplikacji, nie wysyła go na GitHub i nie zapisuje tokenu do Logcat.

Możesz też nacisnąć `USTAW URL` na ekranie aplikacji i wkleić pełny adres przy użyciu klawiatury USB lub ADB input text (ADB może źle traktować niektóre znaki; preferowany jest sposób powyżej).

## Obsługa pilotem

- Przyciski `USTAW URL` i `ODŚWIEŻ` obsługują nawigację pilotem.
- MENU otwiera ustawienia URL (jeśli pilot wysyła kod MENU).
- OK poza przyciskami odświeża dane.
- Aplikacja działa w poziomym trybie pełnoekranowym, nie pozwala ekranowi zasnąć.

## Problemy, diagnostyka

```powershell
.\adb.exe logcat -c
.\adb.exe shell am force-stop pl.fusionsolar.monitor
.\adb.exe shell am start -n pl.fusionsolar.monitor/.MainActivity
.\adb.exe logcat -d -s FusionSolarMonitor:I AndroidRuntime:E
```

Komunikaty nie zawierają URL ani identyfikatora `kk`. Jeśli pojawi się `HTTP 403/404`, sprawdź aktywność linku Kiosk. Jeśli `SSLHandshakeException`, w grę wchodzi TLS albo problem łańcucha certyfikatów na starym Androidzie. Aplikacja łączy się wyłącznie przez HTTPS i nie wyłącza weryfikacji certyfikatów ani hosta.

## Dane i źródła

Kiosk wykorzystuje nieoficjalnie dokumentowany endpoint:

`/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=<identyfikator>`

Odpowiedź JSON zawiera obiekt `realKpi`; Huawei może zmienić API bez zapowiedzi, dlatego nie można zagwarantować działania bez testu na rzeczywistym URL.

- Huawei SmartPVMS manual (Kiosk View), dostępna przez oficjalny portal wsparcia.
- https://github.com/tijsverkoyen/HomeAssistant-FusionSolar (klient Kiosk API)
- https://github.com/DiedB/Homey-SolarPanels/issues/179 (pola `realKpi`)

Java JDK 17, Android Gradle Plugin 8.5.2, Gradle 8.7, compileSdk 34, minSdk 23. Zestaw CA pochodzi z certifi/Mozilla (dostarczony w assets); używany razem z domyślnym systemowym magazynem zaufania.

> Nie jest to oficjalna aplikacja Huawei. Nie jest powiązana ani zatwierdzona przez Huawei.
