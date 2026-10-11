# TerraFill

Clientseitiger Fabric-Mod fuer Minecraft 26.2. Fuellt einen Bereich Ebene fuer
Ebene von unten nach oben mit einem Baublock, mehrere Bloecke pro Tick,
Nachschub aus Shulkerboxen. Schwestermod von DeepMine (gleiche Struktur,
gleicher Chat-Helfer, gleicher Shulker-Zyklus).

## Status

Kompiliert sauber gegen 26.2 (`./gradlew build`, JDK 25 unter
`C:\Program Files\Java\jdk-25.0.4.1`, JAVA_HOME zeigt standardmaessig auf 21).
**Erster Test im Spiel (2026-09-12):** Bauen und Nachschub liefen, aber die abgebaute Shulkerbox blieb liegen - behoben, siehe "Aufsammeln heisst hinlaufen". Rest weiter ungetestet.

**Langer Servertest (2026-09-12, Purpur):** Lief stundenlang, aber drei Hard-Stops zwangen zum Dauernd-Resumen. Aus den Logs (`F:\MultiMc\instances\26.2\.minecraft\logs`) analysiert und vor dem Modrinth-Release gefixt - siehe "Server-Haertung". Vor Modrinth soll das in einer Singleplayer-Kopie noch einmal durchlaufen.

## Struktur

| Datei | Inhalt |
|---|---|
| `TerraFill.java` | Einstiegspunkt, Brigadier-Befehle, Tick-Hook, Start-Pruefungen. |
| `FillTask.java` | Zustandsmaschine FILL / WALK / RESTOCK / BREATHE / FIGHT / HEAL / EAT. Flutung, Ebenen, Setzen, Standortsuche; Kampf, Heilen, Essen wie in DeepMine. |
| `Walker.java` | Breitensuche ueber begehbare Felder plus Schritt-fuer-Schritt-Laufen. Keine Zielzelle, sondern eine Bewertung pro Feld. |
| `Restocker.java` | Nachschub: loser Stapel per SWAP, sonst Shulker setzen/oeffnen/leeren/abbauen/aufsammeln. Meldet `outOfMaterial`, wenn alles leer ist. |
| `Depot.java` | Gang zum Lager: Behaelter im Depot-Bereich suchen, Kiste fuer Kiste leeren, Shulker daneben setzen und fuellen, wieder einstecken. |
| `Region.java` | Quader, reine Daten. |
| `Settings.java` | config/terrafill.json: Slots, Speed, Ecken (nur X/Z), optionale feste Hoehe, Dimension. |
| `Terrain.java` | Oberkante aus dem Gelaende: Median der Bodenhoehe entlang des Randes, unter Baeumen und Wasser hindurch. |
| `Chat.java` | Chat-Ausgaben ueber Sprachschluessel, Farben nach Nachrichtenart. |

Wegen `splitEnvironmentSourceSets()` liegen die Quellen unter `src/client/java/`.
Sprachdateien unter `src/main/resources/assets/terrafill/lang/` (de_de, en_us);
jeder neue Chat-Text braucht beide.

## Multi-Version-Build (26.2 + 26.3)

`./gradlew build` baut 26.2 (Standard), `./gradlew build -Pmc=26.3` die 26.3-Jar.
Die Jar traegt die MC-Version im Namen (`terrafill-1.0.0+26.2.jar`), damit sich
die Varianten nicht ueberschreiben.

- **`mcVersions`-Tabelle in `build.gradle`** ist die einzige Wahrheit: pro
  `-Pmc`-Wert Minecraft-Version, Fabric-API-Version und der Bereich fuer
  `fabric.mod.json`. `minecraft_version`/`fabric_api_version` stehen **nicht**
  mehr in `gradle.properties`. `fabric.mod.json` hat `"minecraft":
  "${minecraft_range}"`, expandiert in `processResources`.
- **Versionsabhaengiger Code** steckt in `Compat` (`Compat.swing(entity)`), je
  eine Fassung unter `src/compat-26.2/java` und `src/compat-26.3/java`;
  `build.gradle` haengt per `sourceSets.client.java.srcDir "src/compat-${mc}/
  java"` genau eine an. Einziger bekannter Unterschied 26.2 -> 26.3:
  `LivingEntity.swing(hand)` wurde zu `swing(hand, SwingAnimation.DEFAULT,
  false)`. **Echter Quellcode, keine Reflection** - Loom remappt die Mod zur
  Laufzeit auf Intermediary, per String angesprochene Methoden/Felder (`swing`,
  `SwingAnimation.DEFAULT`) gaebe es dort nicht.
- **Neue MC-Version aufnehmen:** Eintrag in `mcVersions` ergaenzen; weicht die
  API ab, zusaetzlich `src/compat-<version>/java/.../Compat.java` anlegen (sonst
  faellt der Build mangels Compat-Ordner auf, bzw. man kopiert den passenden).
  CI (`.github/workflows/build.yml`) baut die Matrix `mc: ['26.2', '26.3']`.

## API-Fakten, gegen die 26.2-Jar geprueft

Die Jars liegen im Loom-Cache (`.gradle/loom-cache/minecraftMaven/net/minecraft/`),
`javap -cp` beantwortet jede Signaturfrage in Sekunden. Nicht raten.

- `ItemStack.is(Item)` kommt aus `TypedInstance`, `get(DataComponentType)` aus
  `DataComponentGetter`. Beides da, auch wenn `javap ItemStack` sie nicht zeigt.
- `LevelReader.hasChunkAt`/`hasChunksAt` sind veraltet; `Level.isLoaded(BlockPos)` nicht.
- `LocalPlayer.closeContainer()` schickt das Paket, setzt `containerMenu`
  zurueck und schliesst den Screen - das ist der ganze Vanilla-Weg.
- `LivingEntity.jumpFromGround()` ist public.
- `Player.isWithinBlockInteractionRange(pos, 1.0)` ist genau das, was der
  Server bei `ServerboundUseItemOnPacket` prueft: Auge zur AABB des
  angeklickten Blocks, Reichweite + 1. Keine Sichtlinie.
- `Minecraft.continueAttack(false)` ruft ohne gedrueckte Angriffstaste jeden
  Tick `stopDestroyBlock()`. Das setzt den Fortschritt genau einmal zurueck
  (beim ersten Tick, solange `isDestroying` true ist); danach bleibt
  `destroyBlockPos` gemerkt, `sameDestroyTarget` greift, und
  `continueDestroyBlock` aus dem Tick-Hook summiert den Fortschritt normal
  auf. Der Server akzeptiert das STOP, weil er die Zeit seit START selbst
  zaehlt. Mehrtickiges Abbauen aus dem Tick-Hook funktioniert also ohne Mixin.

## Entscheidungen, die absichtlich so sind

- **Spalten von oben** (Blick von oben, bis zum ersten festen Block) statt
  Flutung: die Flutung kroch durch Loecher im Flussbett seitlich in
  Unterwasserhoehlen. Blaetter werden durchschaut, nicht gefuellt. Weich
  (Haerte 0, oder <= 0.2 ohne Kollision) wird weggeschlagen, alles andere
  Feste gilt als Gelaende.
- **Unerreichbares wird uebersprungen** (`skipped`, `skipRemaining`), nicht
  gestoppt: erst Heranlaufen, dann Lochmodus (nur in einer eingeschlossenen
  Senke), dann abhaken. Der
  Auftrag endet mit der Zahl der uebersprungenen Zellen; `resume` leert die
  Liste und versucht sie neu. Stopp gibt es nur noch, wenn der Walker zweimal
  hintereinander ohne einen gesetzten Block festhaengt (`stuckEpisodes`).
- **Ebenen strikt von unten nach oben**, damit jede Stufe einen Block hoch ist
  und der Walker immer herauskommt. Spaltenweise wuerde den Spieler einmauern.
- **Offene Zellen jeden Tick frisch aus der Welt** (`rebuildRemaining`), keine
  Buchhaltung. Vor dem Ebenenwechsel 15 Ticks Ruhe (`SETTLE_TICKS`), damit
  eine vom Server zurueckgenommene Setzung noch auftaucht.
- **Standortsuche und Setzen stellen dieselbe Frage** (`supportFrom`): gibt es
  eine Klickflaeche in Reichweite des Auges? Sonst verspricht die Suche
  Standorte, an denen nichts geht, und der Mod pendelt.
- **badSpots** sammelt Standorte, an denen nichts ging, bis wieder etwas
  gesetzt wurde. Das garantiert, dass FILL/WALK nicht endlos kreisen.
- **Waehrend des Laufens wird gesetzt**, nur die Felder des Weges (Fuss, Kopf,
  Sprung- und Fallraum) bleiben frei (`pathCells`).
- **Ein Inventarplatz bleibt beim Ausraeumen der Box frei**, sonst kann die
  abgebaute Box nicht wieder aufgesammelt werden. Vor dem Abbauen wird das
  nochmal geprueft (aufgesammelte Drops!); ist der Platz weg, wandert ein
  Stapel zurueck in die Box (`giveBack`, einmal pro Zyklus).
- **Aufsammeln heisst hinlaufen.** `Block.popResource` streut das Item
  +-0.25 Bloecke und gibt ihm Schwung; es liegt danach oft knapp ausserhalb
  der Aufsammel-Reichweite (Spieler-Box +1/+0.5/+1). RECLAIM sucht das
  ItemEntity und laeuft mit `Walker.planTo` darauf. Ohne Box kein Weiterbau:
  nach 15 Sekunden Stopp mit Position. Erste Version lief nach 3 Sekunden
  einfach weiter und hat die Box liegen lassen - genau das ist im Test passiert.
- **Boxplatz mit Bewertung** (`findSpot`): Boden rundherum und kein Wasser
  daneben, damit das Item nicht wegrollt oder wegtreibt.
- **Drei Rueckkehr-Zustaende statt einem.** `resumeState` ist der Bau-Zustand,
  zu dem Heilen zurueckkehrt; `eatReturn` und `fightReturn` merken sich, wo
  Essen bzw. Kampf unterbrochen haben (auch HEAL oder RESTOCK). Mit nur einem
  Feld ueberschreibt Essen-waehrend-Heilen den Bau-Zustand, und Heilen kehrt
  danach zu sich selbst zurueck - dieser Fehler steckt in DeepMines
  `MinerTask` (EAT-Trigger setzt `resumeState = state` auch bei HEAL).
- **Kampf unterbricht den Shulker-Zyklus** ueber `Restocker.interrupt`:
  Kiste zu, Box bleibt stehen, danach OPEN (Box steht), RECLAIM (schon
  abgebaut) oder SWAP (noch nicht gesetzt).
- **Standard-Hotbar wie DeepMine** (1 Schwert, 5 Baumaterial, 8 Essen), damit
  eine Hotbar fuer beide Mods reicht.

## Testen

In einer Singleplayer-Kopie, nicht auf dem Server. Kleines Loch (5x5x2) im
Flachland, Slot 5 mit Cobblestone, Schwert in 1, Essen in 8, eine Shulkerbox
mit ein paar Stapeln, `/terrafill pos1` am einen Rand, `/terrafill pos2` am
anderen, `/terrafill start`.

Die interessantesten Stellen:

1. **Das eigene Loch.** Steht der Spieler auf der Ebene, die gefuellt wird,
   muss er nach dem Zumauern der Nachbarn heraus springen und das eigene Feld
   von oben fuellen (Walker: Stufe hoch braucht `free(x, y+2, z)`).
2. **Shulker-Zyklus.** Wird die Box auf Fusshoehe daneben gesetzt, geht sie
   auf, landen die Stapel im Inventar, geht sie ab und wird sie aufgesammelt?
   Log-Zeile `Kiste zu nach N Stapeln`. Mit vollem Inventar (ein freier Platz)
   muss die Meldung `Inventar voll` kommen, nicht eine Endlosschleife.
3. **Servertempo.** `speed 8` ist Standard; auf dem eigenen Server hochdrehen,
   bis der Server Setzungen zurueckschickt (Zellen tauchen wieder auf).
4. **Wasser.** Teich oder Fluss im Bereich: watet er hinein, taucht er auf,
   bevor die Luft aus ist (`AIR_LOW` = 100 Ticks)?
5. **Kampf mitten im Nachschub.** Zombie spawnen, waehrend die Shulkerbox
   offen ist: Kiste muss zugehen, nach dem Kampf wieder auf, Rest der Stapel
   raus, Box ab und eingesammelt.
6. **Essen waehrend des Heilens.** Leben auf 10, Hunger auf 15: erst Heilen,
   darin Essen, zurueck ins Heilen, bei 16 zurueck zum Bauen - kein Haengen
   im Heilen, keine Meldungsschleife.

## Groessenordnung

50x50 Grundriss, 5 Ebenen = 12.500 Bloecke = 7,2 Shulkerboxen. Reines Setzen
bei 8/Tick: 80 Sekunden. Real dominieren Laufen und Nachschub, rechne mit
5 bis 10 Minuten.

## Hoehe automatisch (seit dem zweiten Test)

pos1/pos2 sind nur noch ein Grundriss. Beim Start liest `Terrain.surfaceLevel`
entlang aller Randspalten die Bodenhoehe ab (`MOTION_BLOCKING`-Heightmap,
dann abwaerts durch Blaetter, Staemme und Wasser bis zum ersten festen Block)
und nimmt den Median als Oberkante. Tiefe: Oberkante minus `AUTO_DEPTH` (48),
die Flutung findet den Grund von selbst. `/terrafill y <oben> [unten]`
uebersteuert, `y auto` stellt zurueck. Median statt Maximum, damit ein Baum
oder eine Flussquerung am Rand die Hoehe nicht verzieht; wer einen Hang
auffuellt, bekommt ein Plateau auf mittlerer Hoehe - das ist gewollt.

Tiere im Feld: `FillTask.blockingAnimal` + `shove` - ein Schlag mit dem
Block in der Hand, hoechstens einer pro Tick, danach greift der normale
20-Tick-Backoff der Zelle. Spieler, Dorfbewohner, Golems, Gegner ausgenommen.

## Festhaengen: Heranlaufen, Lochmodus, Hochmauern (fuenfter Test)

Reihenfolge, wenn `planWalk` keinen Standort findet oder der Walker dreimal
steckenbleibt (`MAX_REPLANS`):

1. `planApproach` (einmal pro Episode, Flag `approached`): Walker mit
   Gewichtung (0, 60, NODES_FAR) zum begehbaren Feld, das der naechsten
   offenen Zelle am naechsten ist. Nur bei "kein Standort".
2. `beginLocal` -> `findHole`: Steht er in einem **Loch**? Ab Fusshoehe wird
   Ebene fuer Ebene die Flutung der Ebenenzellen vom Fussfeld aus gemacht
   (`floodLevel`, 4er-Nachbarn, nur Zellen aus `layers`). Bleibt sie innerhalb
   von `HOLE_RADIUS` (12), ist die Ebene eingeschlossen und gehoert zum Loch;
   die erste Ebene, die den Rand erreicht, beendet es (`holeTop` = die
   darunter). Weil jede Spalte vom Grund bis zur Oberkante offen ist (Blick
   von oben), liegt die Flutung jeder Ebene in der darueber - der Grundriss
   des Lochs ist die Flutung der obersten eingeschlossenen Ebene
   (`holeColumns`), die Zellen sind alle Ebenenzellen in diesen Spalten bis
   `holeTop` (`holeLayers`). Ist schon die Fussebene nicht eingeschlossen
   (offenes Gelaende, Hang, Rand eines Schachts) oder das Fussfeld keine
   Zelle des Auftrags: kein Loch, `false`.
   Im Lochmodus (`local`) zeigt `layerY` auf die unterste Loch-Ebene mit
   Arbeit (`rebuildRemainingLocal`; fertige Ebenen fallen nach `SETTLE_TICKS`
   aus `holeLayers`), `coverage` und `placeReachable` arbeiten unveraendert,
   `placeReachable` setzt lokal zusaetzlich nie ueber Fusshoehe. Ist
   `holeLayers` leer: `endLocal`, `layerY = mainLayerY`, weiter mit der
   Kachel. Kein Standort im Loch: die Loch-Ebene wird abgehakt
   (`skipRemaining`), die naechste kommt von allein - ausser das eigene
   Fussfeld ist noch offen, dann:
3. `beginPillar` (nur im Lochmodus, hoechstens `PILLAR_MAX` = 4 je Episode):
   `jumpFromGround`, und `pillar()` setzt im Sprung das Fussfeld ueber die
   Oberseite des Blocks darunter, sobald die Koerper-AABB (+0.05) das Feld
   nicht mehr schneidet - ein normaler Sprung hebt die Fuesse 1,25 Bloecke,
   das reicht fuer vier Ticks. Nach `PILLAR_TICKS` (20) ohne Erfolg
   aufgegeben. Das ist der einzige Weg aus einem 1x1-Schacht, in den der
   Walker (drei runter, einen hoch) sich absichtlich hineinfallen laesst.
4. Kein Loch und Walker steckt: `stuckEpisodes++`, von vorn planen; beim
   zweiten Mal ohne Fortschritt dazwischen `stop(stuck)`. Kein Loch und kein
   Standort: `skipRemaining` - die Nachpruefung am Kachelende versucht es
   noch einmal.

Warum so und nicht "alles im Umkreis bis Fusshoehe" (die erste Fassung):
die fuellte auf einem Hang oder am Rand eines Schachts ein halbes Plateau
(17x17, bis 64 tief) spaltenweise auf, statt das Loch - "er versucht alles
zu befuellen". Das Loch ist die eingeschlossene Senke, sonst nichts.
`approached`, `local`, `pillars` werden bei jedem Fortschritt bzw.
Start/Resume/Kachelwechsel zurueckgesetzt (`clearLocal`).

## Wegfindung: Tempo (fuenfter Test: "Pathfinding zu langsam")

- `Walker.plan`: alles fastutil (`Long2ObjectOpenHashMap` parent,
  `Long2IntOpenHashMap` depth, `LongOpenHashSet` seen), kein Boxing. Je Suche
  ein Zellencache `Long2ByteOpenHashMap` (SOLID / FREE / BLOCKED / WATER),
  `expand` fragt nur noch den Cache (`cell()` liest jede Position hoechstens
  einmal aus der Welt, `MutableBlockPos` aus einem ThreadLocal). Budgets:
  `NODES_NEAR` (20k) fuer Standortsuche, Loch und Flucht, `NODES_FAR` (150k)
  fuer Heranlaufen, Kachelwechsel und Depot.
- `FillTask.coverage`: offene Zellen als `LongOpenHashSet` plus Grobraster
  8x8 Spalten (`coarseKey`); ein Feld ohne offene Zelle im eigenen und den
  acht Nachbarrastern (COVER_RADIUS 5 < 8) kostet neun Lookups statt 121, und
  Ebenen senkrecht ausser Reichweite kosten gar nichts. Klickflaechen-Cache
  `Long2ByteOpenHashMap` statt `Map<Long,Boolean>`.
- Wartezeiten: `IDLE_LIMIT` 40 -> 10, `SETTLE_TICKS` 30 -> 15.
- `reach` (Settings, 4.0-5.4, Standard 4.5): `FillTask.reach(p)` =
  min(Einstellung, Attribut + 0.9). Der Server prueft Attribut + 1, also
  liegt 5.4 noch drin; mehr Zellen je Standort, weniger Laufen.

## Wasser

- Walker: Wasser ist "frei", begehbar ist der Grund darunter. Im Wasser wird
  die Hoehe direkt gesteuert (`SWIM_UP` 0.3 / `DIVE` -0.25 pro Tick, das
  Wasser nimmt jeden Tick 20 % weg): hoch statt springen, tauchen statt
  absinken (Absinken von allein sind nur 0,5 Bloecke pro Sekunde - das war
  das "halb schwebend bauen"). Am letzten Wegpunkt wird getaucht, bis
  `onGround`.
- `fill()` taucht zuerst ab, wenn der Spieler im Wasser treibt.
- BREATHE hat zwei Varianten: `swimUp` (Spalte ueber dem Kopf ist Wasser,
  dann Luft): `keyJump.setDown(true)` halten - `LocalPlayer.applyInput` setzt
  daraus `jumping`, `LivingEntity.aiStep` macht daraus `jumpInLiquid`
  (+0.04/Tick), an der Oberflaeche bleibt der Kopf so von selbst draussen.
  Loslassen bei voller Luft, bei Stopp und bei Kampfbeginn (`releaseJump`).
  Sonst (Decke drueber) der alte Weg: zum naechsten Feld mit Luft laufen.
- `placeReachable` setzt nie in die eigene Spalte ueber dem Kopf - sonst
  waere der Deckel drauf, bevor das Hochschwimmen anfaengt.
- Nicht gebaut: echtes Schwimmen als Wegfindung (Knoten ohne Grund). Aus
  einem Fluss mit steiler Boeschung kommt er im Zuschuett-Modus ueber die
  Ebenen selbst heraus (jede hebt den Grund um eins, bis die Boeschung eine
  Stufe ist), im Deckel-Modus ueber ESCAPE. Ein Flussbett ist kein "Loch":
  die Flutung der Fussebene laeuft den Fluss entlang ueber HOLE_RADIUS hinaus.
- **Stufe hoch im Wasser** (zweiter Test): schwebend gibt es keine
  Stufenhilfe (`Entity.collide` steigt nur bei `onGround` oder Bodenkontakt
  im selben Move). Also schwimmen, bis die Fuesse UEBER der Kante sind
  (`dy > 0.05`), und tauchen nur weit ueber dem Ziel (`dy < -0.6`) oder am
  letzten Wegpunkt genau darueber (`dist < 0.4`). Die erste Fassung tauchte
  schon "am letzten Wegpunkt schwebend" - auch neben der Stufe, unterhalb
  der Kante - und pendelte dort ewig; STUCK, Lochmodus, eine Ebene zu
  hoch aufgefuellt. Im Wasser wird deshalb auch nur gebaut, wenn er auf dem
  Grund steht (`grounded` in walk(), Abtauchen in fill()).

## Wasser: Deckel statt Zuschuetten (Standard seit dem dritten Test)

`Settings.waterLid` (Befehl `water lid|fill`, Standard lid). Wirkung:

- `scan`: in einer Spalte wird die erste Fluessigkeitszelle noch aufgenommen,
  dann bricht die Spalte ab. Der Deckel liegt also auf Hoehe der
  Wasseroberflaeche, darueber die Luftzellen bis zur Oberkante.
- `Walker.setWaterWalkable(false)`: Wasser ist nicht mehr "frei", der Walker
  betritt es nie. Der Deckel waechst vom Ufer aus und traegt dann selbst.
- ESCAPE-Zustand: steht er trotzdem im Wasser (Start im Fluss, Rueckstoss),
  wird Wasser fuer genau diesen Weg begehbar gemacht und das naechste
  wasserfreie begehbare Feld angesteuert; ohne Ausstieg Stopp mit Position.
  ESCAPE greift nur aus FILL/WALK; BREATHE hat Vorrang (Luft zuerst).
- Uebersprungene Zellen werden nach dem letzten Durchgang noch einmal
  versucht, solange seither etwas gebaut wurde (`placedAtRetry`): die andere
  Flussseite ist erst ueber den Deckel erreichbar.
- Das ganze Tauch-/Schwimmverhalten bleibt fuer `water fill` erhalten.

## Laufen mit Sprint

`Walker.tick`: geradeaus und eben (`straightThrough`: naechster Wegpunkt in
Anfahrtrichtung, gleiche Hoehe) -> `run`: `keyUp.setDown(true)` plus
`setSprinting(true)`; das Spiel bewegt den Spieler entlang des Blicks
(`lookAt` setzt den Yaw zum Wegpunkt). Solche Wegpunkte gelten schon bei
0.35 Bloecken als erreicht (Vorbeilaufen). Alles andere - Kurve, Stufe hoch,
Absatz runter, letzter Wegpunkt, Wasser - laeuft ueber `steer`
(setDeltaMovement, Tempo = min(0.26, Restdistanz)), Toleranz 0.15.
`releaseKeys` bei jedem Wechsel, in `halt` und `clear`. Nicht `keySprint`
benutzen: das ist eine ToggleKeyMapping, `setDown(true)` pro Tick wuerde den
Sprint jeden Tick umschalten. `setSprinting(true)` reicht, Vanilla schickt
das Paket selbst und beendet den Sprint bei Kollision, Hunger <= 6 oder ohne
Vorwaertsimpuls von allein.

## Depot (Nachfuellen am Lager)

`Settings.depotPos1/depotPos2` -> `Region depot()`; `Depot.findContainers`
sucht darin alle Block-Entities, die `Container` sind (Kisten, Faesser,
Shulker; Doppelkisten erscheinen als zwei Positionen, die zweite ist nach
der ersten leer). Ausloeser: `Restocker.outOfMaterial()` in
`FillTask.restock` -> `beginDepot` -> Zustand DEPOT.

Zustandsmaschine in `Depot`: GO (Walker mit Bewertung "Ziel in Reichweite",
Gewichtung (0, 0) = naechster Platz) -> SCAN (nur wenn das Lager beim Start
nicht geladen war) -> OPEN_CHEST -> TAKE (Shift-Klick aus Behaelter-Slots,
ein Platz bleibt frei) -> CLOSE_CHEST -> PLACE_BOX / OPEN_BOX -> PUT
(Shift-Klick aus Spieler-Slots ausser dem Bau-Slot) -> CLOSE_BOX -> zurueck
zur Kiste oder BREAK_BOX -> RECLAIM_BOX (wie Restocker, zum Item laufen).
`goTo(target, afterGo)` ist der gemeinsame Laufschritt fuer Kiste, Box und
Lagerzentrum. Kampf/Essen/Heilen rufen `depot.interrupt`, das Menue geht zu
und der Schritt faellt auf OPEN_CHEST/OPEN_BOX zurueck.

Nicht gebaut: Rueckweg-Planung ueber die Sichtweite hinaus (der Walker
plant nur ueber geladene Chunks; `NODES_FAR` ist dafuer auf 150k) und
Wegbau. Der Weg zum Lager muss begehbar sein.

## Kacheln und Nachpruefung (vierter Test: "Bereich nicht geladen")

- Der Grundriss wird in `buildTiles` in 32x32-Kacheln geschnitten, Schlange ab
  der dem Spieler naechsten Ecke. `region` ist die aktuelle Kachel; Abtastung,
  Ebenen, Laufgrenzen, Lochmodus beziehen sich darauf. `enterTile` setzt
  den Kachelzustand zurueck, `ensureTile` tastet ab, sobald die Kachel geladen
  ist, sonst TRAVEL zur Kachelmitte (`travelToTile`, Walker-Gewichtung wie
  `planApproach`). Keine Vorab-Pruefung "alles geladen" mehr.
- Die Oberkante kommt weiter einmal beim Start aus den geladenen Randspalten
  (`Terrain.surfaceLevel`); bei riesigen Flaechen lieber `/terrafill y <oben>`.
- Ruecknahmen durch den Server: `pending` (Position -> Setz-Tick) wird alle
  5 Ticks geprueft; ein Eintrag aelter als CONFIRM_TICKS (40), der wieder
  fuellbar ist, zaehlt als `reverted` und erhoeht `revertBackoff` (Tempo =
  `speed - revertBackoff`, mindestens 1; alle RECOVER_TICKS (200) ohne
  Ruecknahme einen Schritt zurueck). Grundlage: `BlockStatePredictionHandler`
  stellt beim Ack den Serverstand wieder her, d.h. eine ignorierte Setzung
  wird spaetestens nach der Serverantwort wieder Luft.
- Kachelende: SETTLE_TICKS (15) Ruhe, dann `findWork(true)` von der untersten
  Ebene als Nachpruefung, wiederholt solange seit der letzten Runde etwas
  gebaut wurde (`placedAtRetry`); uebersprungene Zellen werden dabei mit
  zurueckgesetzt. `terrafill.tile.done` meldet Ruecknahmen und Tempo.

## Server-Haertung (sechster Durchgang, vor dem Modrinth-Release)

Drei Hard-Stops aus dem langen Servertest, die den Spieler zum Dauernd-Resumen
zwangen. Alle drei so geloest, dass der Auftrag weiterlaeuft statt abzubrechen.

- **Heilen mauert sich jetzt ein** (`shelter` in `heal`): bei niedrigem Leben
  einen 1x1-Kasten aus dem Baublock (Fuss- und Kopfring plus Deckel), der
  Nahkaempfer und die meisten Geschosse abhaelt - statt offen stehenzubleiben.
  Der alte Sofort-Stopp "Leben < 6 und kein Gegner" ist weg; gestoppt wird nur
  noch, wenn das Leben **trotz** Wand weiter sinkt (`healPrevHp`, erst ab
  healTicks > 40) - also bei Lava/Ertrinken/Ersticken, wo eine Wand nichts
  bringt. Sonst traegt der HEAL_MAX_TICKS-Backstop (1 Minute). Essen fuer die
  Regeneration ab Hunger 18 macht weiter der Ess-Trigger.
- **Essen bricht nicht mehr hart ab** (`eat`): startet das Essen nicht
  innerhalb `EAT_MAX_TICKS` (60), gibt es auf, meldet `eat.stuck` und baut
  weiter; `eatCooldown` (200) verhindert, dass jeder Tick sofort wieder EAT
  triggert. Wird der Hunger wirklich kritisch und ist nichts Essbares da,
  greift weiter der Abbruch in `safetyCheck`. Vorher: nach 120 Ticks harter
  Stopp ("Essen aus Slot 8 klappt nicht"), Schluessel `stop.eat_failed` ist
  entfernt.
- **Verlorene Shulkerbox / kein Platz stoppen nicht mehr sofort**
  (`Restocker.setback` statt `fail` fuer `no_space`, `gone`, `drop_lost`,
  `not_picked_up`; `recoverRestock` in `FillTask`): ist noch Material da
  (`hasMaterial`: loser Stapel oder volle Box), wird neu angesetzt - der
  naechste Zyklus nimmt die naechste Box. Bei Platzmangel (`needSpace`) laeuft
  er vorher ein paar Felder zu einem Platz mit Raum fuer die Box
  (`repositionForBox` -> `boxSpotNear`, dann `restockAfterWalk` in `walk`).
  Nichts mehr da: Depot oder normaler Leer-Abschluss. Erst nach
  `RESTOCK_SETBACK_LIMIT` (3) Pannen hintereinander ohne Fortschritt wirklich
  Stopp. `swap_failed`/`place_failed`/`open_failed`/`break_failed`/`no_room`
  (Inventar voll) bleiben harte Fehler.
