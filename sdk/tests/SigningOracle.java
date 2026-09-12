import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Offline Java-21 protocol oracle. Reads base64 TSV, never connects or loads credentials. */
public class SigningOracle {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String[] fields = line.split("\t", -1);
            List<String> values = new ArrayList<>();
            for (String field : fields) values.add(new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8));
            List<String[]> pairs = new ArrayList<>();
            for (int i = 5; i < values.size(); i += 2) pairs.add(new String[]{values.get(i), values.get(i+1)});
            pairs.sort((a,b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
            String query = pairs.stream().map(p -> URLEncoder.encode(p[0], StandardCharsets.UTF_8) + "=" + URLEncoder.encode(p[1], StandardCharsets.UTF_8)).reduce((a,b) -> a + "&" + b).orElse("");
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values.get(4).trim().getBytes(StandardCharsets.UTF_8)));
            String canonical = String.join("\n", values.get(0).trim().toUpperCase(java.util.Locale.ROOT),values.get(1).trim(),query,values.get(2).trim(),values.get(3).trim(),bodyHash);
            System.out.println(Base64.getEncoder().encodeToString(canonical.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
