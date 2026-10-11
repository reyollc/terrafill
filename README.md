# TerraFill

Clientseitiger Fabric-Mod fuer Minecraft 26.2 und 26.3. Fuellt einen Bereich
Ebene fuer Ebene von unten nach oben mit einem Baublock auf - so schnell, wie
der Server es annimmt. Nachschub kommt aus Shulkerboxen im Inventar, die der
Mod selbst setzt, ausraeumt und wieder einsteckt.

## Bauen

```
./gradlew build               # Standard: Minecraft 26.2
./gradlew build -Pmc=26.3     # fuer Minecraft 26.3
```

JDK 25 noetig; die Jar landet in `build/libs/` und traegt die Ziel-MC-Version
im Namen (`terrafill-1.0.0+26.2.jar` bzw. `...+26.3.jar`). `-Pmc` waehlt
Minecraft- und Fabric-API-Version, den Bereich in `fabric.mod.json` und den
passenden Compat-Quellordner (siehe unten). Unterstuetzte Werte stehen in der
`mcVersions`-Tabelle in `build.gradle` - eine neue MC-Version ist dort ein
Eintrag plus ein `src/compat-<version>/java`, falls die API abweicht.

Braucht Fabric Loader und Fabric API im Client (fuer die jeweilige MC-Version).
Auf dem Server muss nichts installiert sein.

### Mehrere Versionen aus einem Branch

Fast der ganze Code ist versionsunabhaengig. Der einzige API-Unterschied
zwischen 26.2 und 26.3 (`LivingEntity.swing` hat in 26.3 zusaetzliche
Parameter) steckt in einer winzigen `Compat`-Klasse, von der es je eine
Fassung unter `src/compat-26.2/java` und `src/compat-26.3/java` gibt; `-Pmc`
nimmt genau eine davon in den Build. Bewusst echter Quellcode statt Reflection,
weil Loom die Mod zur Laufzeit auf Intermediary-Namen remappt - ueber Reflection
angesprochene Methoden wuerden dort nicht gefunden.

## Bedienung

```
/terrafill pos1 [x z]          Ecke 1 des Grundrisses - ohne Angabe: das Feld, auf dem du stehst
/terrafill pos2 [x z]          Ecke 2 - die Hoehe ist egal
/terrafill y <oben> [unten]    Hoehe fest vorgeben; Standard: automatisch aus dem Gelaende (y auto)
/terrafill start               losbauen
/terrafill stop / resume       anhalten, weitermachen - resume geht auch nach Neustart
/terrafill status              Ebene, offene Zellen, Fortschritt
/terrafill slot <1-9>          Hotbar-Slot mit dem Baublock, Standard 5
/terrafill sword <slot> | off  Schwert, Standard 1 - bei Angriff wird gekaempft, bei wenig Leben geheilt
/terrafill food <slot> | off   Essen, Standard 8 - gegessen wird ab Hunger 12
/terrafill water lid | fill     Wasser nur oben abdecken (Standard) oder bis zum Grund zuschuetten
/terrafill depot pos1 / pos2   Lagerbereich mit den Kisten fuers Nachfuellen (Eckkisten anschauen), depot list / clear
/terrafill speed <1-64>        Bloecke pro Tick, Standard 8 (= 160 pro Sekunde)
/terrafill reach <4.0-5.4>     Reichweite fuers Setzen, Standard 4.5 (der Server nimmt bis 5.4 an)
/terrafill config [reset]      Einstellungen zeigen / zuruecksetzen
```

Die Standard-Hotbar ist dieselbe wie bei DeepMine (1 Schwert, 5 Baumaterial,
8 Essen), damit eine Hotbar fuer beide Mods reicht. Alle Einstellungen landen
bei jeder Aenderung in `config/terrafill.json` und werden beim Spielstart
geladen.

Ablauf: an eine Ecke des Gelaendes stellen, `/terrafill pos1`, zur gegenueber-
liegenden Ecke laufen, `/terrafill pos2`. Das legt nur den Grundriss fest
(X und Z); wie hoch du dabei stehst, ist egal.

Die Hoehe bestimmt der Mod beim Start selbst: Er liest entlang des Randes des
Grundrisses die Bodenhoehe ab (unter Baeumen und Wasser hindurch) und nimmt
den Median als Oberkante - also die Hoehe des Gelaendes, das den Bereich
umgibt. Nach unten geht jede Spalte bis zum Grund, hoechstens 48 Bloecke tief. Wer
etwas anderes will: `/terrafill y <oben>` legt die Oberkante fest,
`/terrafill y <oben> <unten>` beides, `/terrafill y auto` zurueck auf
automatisch. Beim Start steht im Chat, welche Hoehe er genommen hat.

Dann Baublock in Hotbar-Slot 5, Schwert in 1, Essen in 8, Shulkerboxen voll
mit demselben Block ins Inventar, mindestens zwei Plaetze frei lassen,
`/terrafill start`.

## Was gefuellt wird

Was man von oben sieht. Beim Start geht der Mod jede Spalte des Grundrisses
von der Oberkante abwaerts durch alles, was ueberbaubar ist - Luft, Wasser,
Lava, Gras, Schnee, Blumen, Seegras -, bis der erste feste Block kommt. Das
wird gefuellt: Loecher, Taeler, Fluesse. Was unter diesem ersten festen Block
liegt, bleibt unangetastet: Hoehlen, Ueberhaenge, der Gang unter einem Loch
im Flussbett. In eine Unterwasserhoehle geht er also nicht hinein; das Loch
darueber bekommt einen Deckel, so tief er von oben herankommt.

Zellen, an die er von keinem begehbaren Standort aus herankommt (ein tiefer,
enger Schacht), ueberspringt er und meldet das im Chat, statt den Auftrag
anzuhalten. Am Ende steht, wie viele es waren; `/terrafill resume` versucht
sie noch einmal.

Blumen, Setzlinge, Seegras, Seerosen schlaegt er vorher weg. Baumstaemme,
Zaeune und alles andere Feste gelten als Gelaende und bleiben stehen. Durch
Blaetter schaut er hindurch: der Baum bleibt, das Gelaende steigt um ihn
herum.

Wasser bekommt einen Deckel. Bei Fluessen und Seen setzt er nur die oberste
Wasserzelle (plus die Luft darueber bis zur Oberkante); das Wasser darunter
bleibt, wie es ist. Ins Wasser geht er dafuer gar nicht mehr: Der Deckel wird
vom Ufer aus gebaut und dann als Weg benutzt, ueber den er weiter in den Fluss
hinaus baut. Steht er beim Start doch im Wasser, geht er erst ans Ufer. Wer
das Wasser wirklich bis zum Grund zuschuetten will: `/terrafill water fill`
(dann taucht er auf den Grund, laeuft dort, holt Luft durch senkrechtes
Hochschwimmen und setzt nie einen Block ueber dem eigenen Kopf); `water lid`
schaltet zurueck. Kelp und Seegras in der obersten Wasserzelle schlaegt er
wie Blumen vorher weg.

Anmerkung zum Deckel: In Wasser spawnen keine Landmobs, aber Ertrunkene
spawnen in dunklem Wasser durchaus - unter einem Deckel ist es dunkel. Wer
das ausschliessen will, nimmt `water fill`.

Steht ein Tier dort, wo ein Block hin soll, bekommt es einen Schubs mit dem
Block in der Hand - es rennt weg, und eine Sekunde spaeter ist das Feld frei.
Spieler, Dorfbewohner und Eisengolems werden nicht angefasst.

## Wie er baut

Der Grundriss wird in Kacheln von 32x32 zerlegt, die er in Schlangenlinie ab
der Ecke abarbeitet, die dir beim Start am naechsten liegt - jede naechste
Kachel ist die Nachbarin der vorigen, der Weg dorthin fuehrt ueber fertiges
Gelaende. Eine Kachel wird erst abgetastet, wenn er dort ist und ihre Chunks
geladen sind; deshalb duerfen Bereiche beliebig groesser als die Sichtweite
sein (bis 1024x1024). Innerhalb einer Kachel gilt:

Ebene fuer Ebene von unten nach oben. Auf jeder Ebene setzt er alles, was von
seinem Standort aus in Reichweite ist, mehrere Bloecke pro Tick, und sucht sich
dann den naechsten Standort: den mit der besten Bilanz aus vielen baubaren
Zellen und kurzem Weg. Dorthin sprintet er ueber die Oberflaeche - eine Stufe
hoch per Sprung, bis drei Bloecke runter, durch Wasser watend oder tauchend
(Luft holen inklusive). Unterwegs setzt er weiter, was in Reichweite kommt.

Steht er selbst auf der Ebene, die gerade gefuellt wird, entsteht um ihn ein
Loch von einem Block Tiefe; er springt heraus und fuellt es von oben. Deshalb
wird jede Ebene in einem Rutsch fertig, bevor die naechste beginnt.

**Wenn er festhaengt** - kein Standort erreichbar, oder er kommt beim Laufen
nicht vom Fleck - laeuft er erst so nah wie moeglich an die offenen Zellen
heran. Reicht das nicht, kommt es darauf an, wo er steht:

- **In einem Loch**, also in einer Senke, die auf seiner Fusshoehe rundherum
  von festem Gelaende eingeschlossen ist (hoechstens 12 Bloecke weit):
  Lochmodus. Er fuellt nur dieses Loch, Ebene fuer Ebene von unten, und
  steigt Stufe fuer Stufe mit nach oben, bis die Senke oben in offenes
  Gelaende uebergeht. Dann geht es normal weiter. Bleibt in einer Ebene nur
  noch sein eigenes Fussfeld uebrig und er kommt nicht heraus (Schacht),
  mauert er sich hoch: springen, im Sprung den Block unter die Fuesse setzen.
- **Auf offenem Gelaende** gibt es nichts aufzufuellen, was ihn frei machen
  wuerde. Die unerreichbaren Zellen werden uebersprungen und am Ende der
  Kachel noch einmal versucht; kommt er beim Laufen zweimal hintereinander
  nicht vom Fleck, stoppt er mit Position.

Frueher fuellte der lokale Modus alles im Umkreis von acht Bloecken bis
Fusshoehe auf - auf einem Hang oder am Rand eines Schachts war das ein
halbes Plateau statt eines Lochs. Jetzt zaehlt nur die eingeschlossene Senke.

## Nachschub

Ist der Slot leer, tauscht der Mod erst einen losen Stapel aus dem Inventar
hinein. Gibt es keinen mehr, setzt er eine Shulkerbox neben sich, oeffnet sie,
holt Stapel per Shift-Klick, schliesst, baut sie ab und steckt sie wieder ein.
Ein Inventarplatz bleibt dabei immer frei fuer die Box selbst - deshalb
**mindestens zwei freie Plaetze** lassen, sonst kann er nichts holen. Je mehr
Platz frei ist, desto seltener muss er an die Box.

Die Box wird moeglichst dort gesetzt, wo rundherum Boden und kein Wasser ist,
weil das abgebaute Item einen halben Block weit huepft. Ist der letzte freie
Platz inzwischen durch aufgesammeltes Kelp oder Mob-Drops belegt, legt er vor
dem Abbauen einen Stapel in die Box zurueck. Nach dem Abbauen laeuft er zum
Item hin und steht darauf, bis es im Inventar ist. Klappt das binnen 15
Sekunden nicht, stoppt er mit der Position - er laeuft nie ohne die Box weiter.

Sind alle Stapel und alle Boxen leer, stoppt er und nennt seine Position -
es sei denn, ein Depot ist gesetzt.

## Depot: Nachfuellen am Lager

`/terrafill depot pos1` und `pos2` markieren den Lagerbereich, in dem die
Kisten stehen - z.B. die Kistenwand am Cobblestone-Generator. Ohne
Koordinaten wird der Block genommen, den du gerade anschaust (also die
Eckkiste), sonst das Feld, auf dem du stehst; mit `pos1 x y z` geht es auch
von Hand. Die Hoehe zaehlt mit, weil Kisten gestapelt sind. Alles mit
Inventar im Bereich gilt als Behaelter: Kisten, Doppelkisten, Faesser,
Shulkerboxen. `depot list` zeigt den Bereich und, soweit geladen, wie viele
Behaelter darin sind; `depot clear` loescht ihn.

Sind unterwegs alle Shulker leer, laeuft er zum Lager (liegt es ausserhalb
der Sichtweite, erst hin und dort suchen), geht Kiste fuer Kiste durch und
fuellt die Shulker: Stapel per Shift-Klick aus der Kiste ins Inventar, eine
leere oder angefangene Shulkerbox daneben setzen, die Stapel hinein, zurueck
zur Kiste - bis die Box voll ist, dann abbauen, einstecken, naechste Box.
Leere Kisten ueberspringt er still. Der Bau-Slot behaelt seinen Stapel,
damit es nach der Rueckkehr sofort weitergeht. Wenn alle Shulker voll sind
oder das Lager leer ist, laeuft er zurueck und macht da weiter, wo er war.

Bedingungen: Der Weg zum Lager muss zu Fuss gehen (Stufen bis einen Block
hoch, Abspruenge bis drei); hoehere Kanten oder Wasser ohne Deckel dazwischen
brechen den Gang ab. Mindestens zwei freie Inventarplaetze, wie beim
Nachschub. Er fuellt nur Shulker, die leer sind oder nur den Baublock
enthalten.

Alle Einstellungen - Bereich, Hoehe, Slots, Wasser-Modus, Depot - liegen in
`config/terrafill.json` und ueberleben Serverwechsel und Neustart.

## Kampf, Heilen, Essen

Wie in DeepMine. Kommt ein feindlicher Mob auf 4 Bloecke heran oder kommt
Schaden an, zieht er das Schwert und kaempft: zuschlagen nur bei vollem
Angriffsbalken, auf Gegner ausser Reichweite Schritt fuer Schritt zugehen
(nie auf Creeper, nie ueber ein Loch), nach 2 Sekunden ohne Ziel weiterbauen.
Faellt das Leben unter 12 und kein Gegner ist zu sehen, bleibt er stehen, bis
es wieder ueber 16 ist. Bei Hunger 12 (beim Heilen schon bei 17) isst er aus
dem Essens-Slot. Eine gerade offene Shulkerbox wird fuer den Kampf zugemacht
und danach an derselben Stelle weiter ausgeraeumt.

Ohne Schwert (`/terrafill sword off`) wird nicht gekaempft, dann ist Leben
unter 12 ein Abbruchgrund. Ohne Essen ist Hunger unter 6 einer.

## Was er von allein abbricht

Tod, Dimensionswechsel, Leben unter 12 ohne Schwert, Hunger unter 6 ohne
Essen, Leben faellt unter 6 ohne sichtbaren Gegner, eine Minute ohne Erholung,
Material alle, Shulkerbox laesst sich nicht setzen/oeffnen/abbauen, keine Luft
und kein Weg nach oben, oder offene Zellen, an die er weder herankommt noch
sich heranfuellen kann (Heranlaufen, Lochmodus und Hochmauern haben nichts mehr
gebracht). In allen Faellen steht die Position im Chat; selbst hinlaufen, ggf.
eine Treppe bauen, dann `/terrafill resume`.

## Grenzen

- Grundriss hoechstens 1024x1024. Pro Kachel hoechstens 2.000.000 zu fuellende Bloecke (bei 32x32 nie ein Thema).
- Auf dem Server nimmt er bei zu hohem Tempo Setzungen zurueck; das erkennt der
  Mod und drosselt sich selbst (siehe "Nachpruefung"). Hilft das nicht, `speed` runter.
- Der Baublock sollte ein normaler voller Block sein. Sand und Kies fallen in
  Hohlraeume darunter und werden dort endlos nachgefuellt.
- Gekaempft wird nur, was in Sichtlinie ist. Ein Skelett auf der anderen
  Seite des Flusses oder ein Creeper, der von hinten kommt, ist ein Risiko -
  Ruestung tragen.
- `speed` ist die Zahl der Setz-Pakete pro Tick. Vanilla und Paper nehmen das
  ohne Murren; ein Anti-Cheat kann bei hohen Werten oder bei der Fortbewegung
  anschlagen. Dann `speed` runter.

## Bewusste Entscheidungen

**Setzen ueber `gameMode.useItemOn`, Abbauen ueber `continueDestroyBlock`,
Kisten ueber `handleContainerInput`.** Das sind die Wege, die Vanilla beim
Rechtsklick, beim Halten der linken Maustaste und beim Klick im Menue nimmt.
Keine handgebauten Pakete. Der Server prueft beim Setzen nur die Reichweite
zum angeklickten Block (4,5 + 1 Toleranz) und keine Sichtlinie - deshalb geht
"mehrmals pro Tick", ohne dass etwas abgelehnt wird. Aus derselben Pruefung
kommt `reach`: die Toleranz ist fuer die Latenz gedacht, aber der Server
nimmt bis 5,5 an; wer `reach 5.4` setzt, baut pro Standort mehr und laeuft
weniger. Standard bleibt das Vanilla-Attribut 4,5.

**Spalten von oben statt Flutung.** Ein Quader von zwei Ecken wuerde jede
Hoehle darunter mitfuellen; eine Flutung von der Oberkante aus kroch durch
jedes Loch im Flussbett seitlich in Unterwasserhoehlen hinein - so war es
zuerst gebaut, und genau das hat gestoert. Der Blick von oben, Spalte fuer
Spalte bis zum ersten festen Block, trifft, was man meint, wenn man
"Gelaende auffuellen" sagt.

**Ebenen strikt von unten nach oben.** Wuerde er spaltenweise bauen, staende er
bald in einem Turm aus Waenden, aus dem kein Sprung mehr herausfuehrt. Mit
einer Ebene pro Durchgang ist jede Stufe genau einen Block hoch.

**Klickflaeche bevorzugt unten.** Beim Fuellen von unten ist der Block darunter
fast immer da; das haelt auch die Reichweite berechenbar. Bloecke mit
Block-Entity und die bekannten Rechtsklick-Bloecke (Tueren, Knoepfe,
Werkbank ...) werden als Klickflaeche gemieden, sonst gingen sie auf, statt
dass gesetzt wird.

**Offene Zellen jeden Tick frisch aus der Welt.** Nimmt der Server eine Setzung
zurueck, taucht die Zelle von selbst wieder auf; ist Kies nachgerutscht oder
Wasser gefroren, gilt die Zelle als erledigt. Keine Buchhaltung, die
auseinanderlaufen kann.

**Nachpruefung statt Nacharbeit.** Der Client setzt jeden Block sofort als
Vorhersage; der Server bestaetigt ihn oder nimmt ihn nach seiner Antwort
zurueck. Auf einem ausgelasteten Server kommt die Ruecknahme spaet - war der
Mod da schon eine Ebene weiter, blieb frueher ein Loch, ueber das man
hinterher selbst laufen musste. Jetzt: 1,5 Sekunden Ruhe nach dem letzten
Setzen, bevor eine Ebene als fertig gilt; jeder frisch gesetzte Block wird
zwei Sekunden lang beobachtet, und taucht er wieder als Luft auf, drosselt
der Mod sein Tempo um einen Block pro Tick (erholt sich alle 10 Sekunden
ohne Ruecknahme um einen); und am Ende jeder Kachel geht er alle Ebenen
noch einmal von unten durch, bis eine Runde nichts mehr findet. Die Meldung
zur fertigen Kachel nennt, wie viele Bloecke der Server zurueckgenommen hat
und mit welchem Tempo er gerade faehrt.

**Laufen wie ein Spieler, ankommen wie ein Roboter.** Auf geraden, ebenen
Strecken haelt er die Vorwaertstaste und sprintet - das Spiel macht
Beschleunigung, Reibung und Animation. Vor Kurven, Stufen, Absaetzen und dem
Ziel schaltet er auf die genaue Steuerung ueber `setDeltaMovement` um (wie in
DeepMine): mit Sprint-Schwung kaeme er weder sauber um die Ecke noch in der
Feldmitte an, und beim Runterspringen truege ihn der Schwung ueber das
Landefeld hinaus. Sprint braucht Hunger ueber 6; Essen ab 12 sorgt dafuer.

**Kampf hat Vorrang vor allem**, auch vor den Abbruchgruenden. Wer bei
niedrigem Leben stehen bleibt statt sich zu wehren, stirbt - das ist die
Lehre aus DeepMine. Heilen und Essen sind Wartezustaende, die danach dorthin
zurueckkehren, wo sie unterbrochen haben.
