package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import reporting.ExecutionInfo;

/**
 * Names for the clients the automated tests CREATE (the happy flow and the edge-case class): ANIME names.
 *
 *  - client (legal entity) name = the anime, e.g. "Dragon Ball Z"
 *  - contact person at registration = the MAIN CHARACTER of that anime ("Son Goku"), e-mail = the name in lower case + @yopmail.com
 *    (songoku@yopmail.com), which is also the client's login address
 *  - Business / Finance / HR contacts of the wizard = three other characters of the same anime, e-mail built the same way
 *
 * To be sure a name does not exist yet, every client the tests create is written to a text file
 * (testdata/existing-clients.txt, key client.names.file). Before a new client is registered the file is READ and an anime that is
 * not in it is picked; right after the registration the client is appended. So the same anime is never used twice and nothing has
 * to be looked up in the application first. This registry is separate from the duplicate-name test cases (R-30 ...), which look at
 * the clients that already exist in the Clients list.
 *
 * To free a name again delete its line in the file. When all anime are used the list is used again with a number ("Naruto 2",
 * e-mails narutouzumaki2@yopmail.com). If the application still says the name or an e-mail exists (somebody created it by hand), the anime is added
 * to the file and the next one is used.
 */
public final class ClientNames {

    /** One anime with four characters: the first is the MAIN character. Plain letters and spaces only (used in locators, searches and e-mail addresses). */
    private static final String[][] ANIME = {
        { "Dragon Ball Z", "Son Goku", "Vegeta", "Son Gohan", "Piccolo" },
        { "Naruto", "Naruto Uzumaki", "Sasuke Uchiha", "Sakura Haruno", "Kakashi Hatake" },
        { "One Piece", "Monkey D Luffy", "Roronoa Zoro", "Nami", "Sanji" },
        { "Bleach", "Ichigo Kurosaki", "Rukia Kuchiki", "Orihime Inoue", "Uryu Ishida" },
        { "Death Note", "Light Yagami", "Misa Amane", "Ryuk", "Mello" },
        { "Attack on Titan", "Eren Yeager", "Mikasa Ackerman", "Armin Arlert", "Levi Ackerman" },
        { "Fullmetal Alchemist Brotherhood", "Edward Elric", "Alphonse Elric", "Winry Rockbell", "Roy Mustang" },
        { "Hunter x Hunter", "Gon Freecss", "Killua Zoldyck", "Kurapika", "Leorio Paradinight" },
        { "Demon Slayer", "Tanjiro Kamado", "Nezuko Kamado", "Zenitsu Agatsuma", "Inosuke Hashibira" },
        { "My Hero Academia", "Izuku Midoriya", "Katsuki Bakugo", "Ochaco Uraraka", "Shoto Todoroki" },
        { "Jujutsu Kaisen", "Yuji Itadori", "Megumi Fushiguro", "Nobara Kugisaki", "Satoru Gojo" },
        { "Tokyo Ghoul", "Ken Kaneki", "Touka Kirishima", "Rize Kamishiro", "Hide Nagachika" },
        { "Sword Art Online", "Kirito", "Asuna Yuuki", "Sinon", "Klein" },
        { "Fairy Tail", "Natsu Dragneel", "Lucy Heartfilia", "Erza Scarlet", "Gray Fullbuster" },
        { "Cowboy Bebop", "Spike Spiegel", "Jet Black", "Faye Valentine", "Edward Wong" },
        { "Steins Gate", "Rintaro Okabe", "Kurisu Makise", "Mayuri Shiina", "Itaru Hashida" },
        { "Code Geass", "Lelouch Lamperouge", "Suzaku Kururugi", "Kallen Stadtfeld", "Nunnally Lamperouge" },
        { "One Punch Man", "Saitama", "Genos", "Tatsumaki", "Fubuki" },
        { "Black Clover", "Asta", "Yuno", "Noelle Silva", "Yami Sukehiro" },
        { "Haikyuu", "Shoyo Hinata", "Tobio Kageyama", "Kei Tsukishima", "Koshi Sugawara" },
        { "Gintama", "Gintoki Sakata", "Shinpachi Shimura", "Kagura", "Toshiro Hijikata" },
        { "Mob Psycho 100", "Shigeo Kageyama", "Arataka Reigen", "Ritsu Kageyama", "Teruki Hanazawa" },
        { "Chainsaw Man", "Denji", "Power", "Aki Hayakawa", "Makima" },
        { "Spy x Family", "Loid Forger", "Yor Forger", "Anya Forger", "Bond Forger" },
        { "Vinland Saga", "Thorfinn", "Askeladd", "Canute", "Thorkell" },
        { "Neon Genesis Evangelion", "Shinji Ikari", "Rei Ayanami", "Asuka Langley", "Misato Katsuragi" },
        { "Fire Force", "Shinra Kusakabe", "Arthur Boyle", "Maki Oze", "Iris" },
        { "Dr Stone", "Senku Ishigami", "Taiju Oki", "Yuzuriha Ogawa", "Tsukasa Shishio" },
        { "Re Zero", "Subaru Natsuki", "Emilia", "Rem", "Ram" },
        { "Konosuba", "Kazuma Satou", "Aqua", "Megumin", "Darkness" },
        { "Overlord", "Ainz Ooal Gown", "Albedo", "Shalltear Bloodfallen", "Demiurge" },
        { "Toradora", "Ryuji Takasu", "Taiga Aisaka", "Minori Kushieda", "Ami Kawashima" },
        { "Clannad", "Tomoya Okazaki", "Nagisa Furukawa", "Kyou Fujibayashi", "Tomoyo Sakagami" },
        { "Your Name", "Taki Tachibana", "Mitsuha Miyamizu", "Yukari Yukino", "Katsuhiko Teshigawara" },
        { "Spirited Away", "Chihiro Ogino", "Haku", "Yubaba", "Kamaji" },
        { "Princess Mononoke", "Ashitaka", "San", "Lady Eboshi", "Jigo" },
        { "Howls Moving Castle", "Sophie Hatter", "Howl Jenkins", "Calcifer", "Markl" },
        { "Akira", "Shotaro Kaneda", "Tetsuo Shima", "Kei", "Ryu" },
        { "Ghost in the Shell", "Motoko Kusanagi", "Batou", "Togusa", "Aramaki" },
        { "Trigun", "Vash the Stampede", "Nicholas Wolfwood", "Meryl Stryfe", "Millie Thompson" },
        { "Samurai Champloo", "Mugen", "Jin", "Fuu", "Kariya Kagetoki" },
        { "Rurouni Kenshin", "Kenshin Himura", "Kaoru Kamiya", "Sanosuke Sagara", "Yahiko Myojin" },
        { "Yu Yu Hakusho", "Yusuke Urameshi", "Kazuma Kuwabara", "Kurama", "Hiei" },
        { "Inuyasha", "Inuyasha Taisho", "Kagome Higurashi", "Miroku", "Sango" },
        { "Sailor Moon", "Usagi Tsukino", "Ami Mizuno", "Rei Hino", "Makoto Kino" },
        { "Cardcaptor Sakura", "Sakura Kinomoto", "Tomoyo Daidouji", "Syaoran Li", "Kero" },
        { "Pokemon", "Ash Ketchum", "Misty", "Brock", "Pikachu" },
        { "Digimon", "Tai Kamiya", "Matt Ishida", "Sora Takenouchi", "Izzy Izumi" },
        { "Slam Dunk", "Hanamichi Sakuragi", "Kaede Rukawa", "Takenori Akagi", "Hisashi Mitsui" },
        { "Hajime no Ippo", "Ippo Makunouchi", "Mamoru Takamura", "Masaru Aoki", "Tatsuya Kimura" },
        { "Berserk", "Guts", "Griffith", "Casca", "Puck" },
        { "Monster", "Kenzo Tenma", "Johan Liebert", "Nina Fortner", "Eva Heinemann" },
        { "Psycho Pass", "Akane Tsunemori", "Shinya Kogami", "Nobuchika Ginoza", "Shogo Makishima" },
        { "Parasyte", "Shinichi Izumi", "Migi", "Satomi Murano", "Ryoko Tamiya" },
        { "Blue Exorcist", "Rin Okumura", "Yukio Okumura", "Shiemi Moriyama", "Ryuji Suguro" },
        { "Soul Eater", "Maka Albarn", "Soul Evans", "Black Star", "Death the Kid" },
        { "Bakuman", "Moritaka Mashiro", "Akito Takagi", "Miho Azuki", "Eiji Niizuma" },
        { "Assassination Classroom", "Nagisa Shiota", "Koro Sensei", "Karma Akabane", "Kaede Kayano" },
        { "Food Wars", "Soma Yukihira", "Erina Nakiri", "Megumi Tadokoro", "Takumi Aldini" },
        { "The Promised Neverland", "Emma", "Norman", "Ray", "Isabella" },
        { "Dororo", "Hyakkimaru", "Dororo", "Tahomaru", "Jukai" },
        { "Kaguya Sama", "Kaguya Shinomiya", "Miyuki Shirogane", "Chika Fujiwara", "Yu Ishigami" },
        { "Horimiya", "Kyoko Hori", "Izumi Miyamura", "Yuki Yoshikawa", "Toru Ishikawa" },
        { "Violet Evergarden", "Violet Evergarden", "Gilbert Bougainvillea", "Claudia Hodgins", "Cattleya Baudelaire" },
        { "A Silent Voice", "Shoya Ishida", "Shoko Nishimiya", "Naoka Ueno", "Tomohiro Nagatsuka" },
        { "Weathering with You", "Hodaka Morishima", "Hina Amano", "Keisuke Suga", "Nagi Amano" },
        { "Akame ga Kill", "Tatsumi", "Akame", "Leone", "Mine" },
        { "Seven Deadly Sins", "Meliodas", "Elizabeth Liones", "Ban", "Diane" },
        { "Noragami", "Yato", "Hiyori Iki", "Yukine", "Kazuma" },
        { "Bungo Stray Dogs", "Atsushi Nakajima", "Osamu Dazai", "Kyoka Izumi", "Ryunosuke Akutagawa" },
        { "Mushishi", "Ginko", "Tanyu", "Adashino", "Nui" },
        { "Anohana", "Jinta Yadomi", "Menma", "Naruko Anjo", "Yukiatsu Matsuyuki" },
        { "Angel Beats", "Otonashi Yuzuru", "Yuri Nakamura", "Kanade Tachibana", "Hideki Hinata" },
        { "Tokyo Revengers", "Takemichi Hanagaki", "Manjiro Sano", "Ken Ryuguji", "Draken" },
        { "Dungeon Meshi", "Laios Touden", "Marcille Donato", "Chilchuck Tims", "Senshi" },
        { "Frieren", "Frieren", "Fern", "Stark", "Himmel" },
        { "Solo Leveling", "Sung Jinwoo", "Cha Haein", "Go Gunhee", "Baek Yoonho" },
        { "Oshi no Ko", "Aqua Hoshino", "Ruby Hoshino", "Ai Hoshino", "Kana Arima" },
        { "Bocchi the Rock", "Hitori Gotoh", "Nijika Ijichi", "Ryo Yamada", "Ikuyo Kita" },
        { "Lycoris Recoil", "Chisato Nishikigi", "Takina Inoue", "Mika", "Kurumi" },
        { "Kingdom", "Shin", "Ei Sei", "Wang Qi", "Kyou Kai" },
        { "Golden Kamuy", "Saichi Sugimoto", "Asirpa", "Yoshitake Shiraishi", "Hajime Tsukishima" },
        { "Made in Abyss", "Riko", "Reg", "Nanachi", "Ozen" },
        { "Initial D", "Takumi Fujiwara", "Ryosuke Takahashi", "Keisuke Takahashi", "Itsuki Takeuchi" },
        { "Captain Tsubasa", "Tsubasa Ozora", "Genzo Wakabayashi", "Taro Misaki", "Kojiro Hyuga" },
        { "Beyblade", "Tyson Granger", "Kai Hiwatari", "Ray Kon", "Max Tate" },
        { "Black Lagoon", "Rock Okajima", "Revy", "Dutch", "Benny" },
        { "Hellsing", "Alucard", "Integra Hellsing", "Seras Victoria", "Walter Dornez" },
        { "Claymore", "Clare", "Raki", "Teresa", "Miria" },
        { "Fate Zero", "Kiritsugu Emiya", "Saber", "Kirei Kotomine", "Waver Velvet" },
        { "Nana", "Nana Osaki", "Nana Komatsu", "Ren Honjo", "Nobuo Terashima" },
        { "Fruits Basket", "Tohru Honda", "Yuki Sohma", "Kyo Sohma", "Shigure Sohma" },
        { "Ouran High School Host Club", "Haruhi Fujioka", "Tamaki Suoh", "Kyoya Ootori", "Hikaru Hitachiin" },
        { "Great Teacher Onizuka", "Eikichi Onizuka", "Azusa Fuyutsuki", "Ryuji Danma", "Tomoko Nomura" },
        { "Mobile Suit Gundam", "Amuro Ray", "Char Aznable", "Bright Noa", "Fraw Bow" },
        { "Voltron", "Keith", "Lance", "Pidge", "Hunk" },
        { "Saint Seiya", "Seiya", "Shiryu", "Hyoga", "Shun" },
        { "Fist of the North Star", "Kenshiro", "Raoh", "Rei Nanami", "Toki" },
        { "Gurren Lagann", "Simon", "Kamina", "Yoko", "Nia" },
        { "Eureka Seven", "Renton Thurston", "Eureka", "Holland", "Talho Yuki" },
        { "Elfen Lied", "Lucy", "Kouta", "Nana", "Yuka" },
        { "Blue Lock", "Yoichi Isagi", "Meguru Bachira", "Seishiro Nagi", "Rin Itoshi" },
        { "Mushoku Tensei", "Rudeus Greyrat", "Roxy Migurdia", "Eris Greyrat", "Sylphiette" },
        { "Shield Hero", "Naofumi Iwatani", "Raphtalia", "Filo", "Melty Melromarc" },
        { "No Game No Life", "Sora", "Shiro", "Stephanie Dola", "Jibril" },
        { "Log Horizon", "Shiroe", "Naotsugu", "Akatsuki", "Nyanta" },
        { "Fate Stay Night", "Shirou Emiya", "Rin Tohsaka", "Sakura Matou", "Illyasviel" },
        { "Durarara", "Mikado Ryugamine", "Masaomi Kida", "Anri Sonohara", "Izaya Orihara" },
        { "Baccano", "Isaac Dian", "Miria Harvent", "Jacuzzi Splot", "Firo Prochainezo" },
        { "Little Witch Academia", "Atsuko Kagari", "Lotte Yanson", "Sucy Manbavaran", "Diana Cavendish" },
    };

    /** One pick: the anime (= client legal name) and its four characters, main character first. */
    public static final class Pick {
        public final String name;
        public final String[] characters;
        private final String emailSuffix;

        Pick(String name, String[] characters, String emailSuffix) {
            this.name = name;
            this.characters = characters;
            this.emailSuffix = emailSuffix;
        }

        /** E-mail of character i: the name in lower case without spaces + @yopmail.com (Son Goku -> songoku@yopmail.com). */
        public String email(int i) {
            return characters[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + emailSuffix + "@yopmail.com";
        }
    }

    private static final SecureRandom RND = new SecureRandom();
    private static final String HEADER = String.join("\n",
            "# Clients created by the automated tests (client name | e-mail | created by | date time).",
            "# The tests READ this file before registering a client and pick an anime that is not listed, then APPEND the new client here,",
            "# so a name is never used twice. Only the text before the first '|' is the client name. Delete a line to free its name again.",
            "");

    private ClientNames() {
    }

    private static Path file() {
        return Paths.get(ConfigManager.get("client.names.file", "testdata/existing-clients.txt"));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Names already used (normalised: trimmed, single spaces, lower case). */
    public static synchronized Set<String> used() {
        Set<String> out = new LinkedHashSet<>();
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int bar = t.indexOf('|');
                out.add(norm(bar < 0 ? t : t.substring(0, bar)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the client names file " + f + ": " + e.getMessage(), e);
        }
        return out;
    }

    /** The client name the file lists for this login e-mail (case-insensitive), or null when the e-mail is not listed. */
    public static synchronized String nameForEmail(String email) {
        Path f = file();
        if (email == null || !Files.isRegularFile(f)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] part = line.trim().split("\\|");
                if (!line.trim().startsWith("#") && part.length >= 2 && part[1].trim().equalsIgnoreCase(email.trim())) {
                    return part[0].trim();
                }
            }
        } catch (IOException e) {
            Log.warn("Client names file not read: " + e.getMessage());
        }
        return null;
    }

    /** An anime (with its characters) that is not in the file yet. Nothing is written: the client is recorded when it really exists. */
    public static synchronized Pick pick() {
        Set<String> used = used();
        for (int round = 1; round <= 200; round++) {
            List<Pick> free = new ArrayList<>();
            for (String[] row : ANIME) {
                String candidate = round == 1 ? row[0] : row[0] + " " + round;
                if (!used.contains(norm(candidate))) {
                    free.add(new Pick(candidate, new String[] { row[1], row[2], row[3], row[4] }, round == 1 ? "" : String.valueOf(round)));
                }
            }
            if (!free.isEmpty()) {
                return free.get(RND.nextInt(free.size()));
            }
        }
        throw new IllegalStateException("All anime client names are used up in " + file() + ": delete some lines to free names.");
    }

    /** Writes the client to the file right after its registration succeeded (nothing happens when the name is already listed). */
    public static synchronized void record(String name, String email) {
        append(name, email, ExecutionInfo.className() == null ? "-" : ExecutionInfo.className());
    }

    /** The application said this name / e-mail exists although the file did not list it: list it so it is not picked again. */
    public static synchronized void markUsed(String name, String why) {
        append(name, "-", why);
    }

    private static void append(String name, String email, String by) {
        if (name == null || name.isBlank() || used().contains(norm(name))) {
            return;
        }
        Path f = file();
        try {
            if (f.toAbsolutePath().getParent() != null) {
                Files.createDirectories(f.toAbsolutePath().getParent());
            }
            String line = name.trim() + " | " + email + " | " + by + " | "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a")) + "\n";
            if (!Files.isRegularFile(f)) {
                Files.writeString(f, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.warn("Client name '" + name + "' could not be written to " + f + ": " + e.getMessage());
        }
    }

    /** The application's answer to a legal entity name that already exists ("Client with this organization name is already exist!"). */
    public static boolean isDuplicateNameMessage(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return m.contains("already") && (m.contains("organization name") || m.contains("organisation name"));
    }

    /** The application's answer to an e-mail address that is already registered (any wording with "already" and "e-mail" / "email"). */
    public static boolean isDuplicateEmailMessage(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return m.contains("already") && (m.contains("email") || m.contains("e-mail"));
    }

    /** Name or e-mail already exists: the next anime must be used. */
    public static boolean isDuplicateMessage(String message) {
        return isDuplicateNameMessage(message) || isDuplicateEmailMessage(message);
    }
}
