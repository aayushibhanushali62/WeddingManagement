import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class WeddingManagement {

    // =========================================================
    //  GLOBAL STATE (admin + all users)
    // =========================================================
    private static final List<Booking> ALL_BOOKINGS = new ArrayList<>();
    private static final Map<String, String> VENUE_DATE_REGISTRY = new HashMap<>(); // "venueIdx|dd-MM-yyyy" -> bookingId
    private static final Map<String, Customer> USERS = new HashMap<>(); // email -> Customer
    private static final Map<String, List<String>> WAITLIST = new HashMap<>(); // "venueIdx|dd-MM-yyyy" -> List<email>

    private static final String ADMIN_EMAIL = "admin@wedding.com";
    private static final String ADMIN_PASSWORD = "Admin@123";

    private static final Map<String, Integer> COUPONS = new LinkedHashMap<>();
    static {
        COUPONS.put("SAVE10", 10);
        COUPONS.put("WEDDING20", 20);
        COUPONS.put("FIRSTBOOK", 15);
        COUPONS.put("ROYAL30", 30);
    }

    // =========================================================
    //  DYNAMIC CONFIGURATIONS (Venues can be added/removed)
    // =========================================================
    static final List<String> VENUE_NAMES = new ArrayList<>(Arrays.asList(
            "Community Hall", "Banquet Hall", "5-Star Hotel Ballroom", "Beachside Resort"
    ));
    static final List<Integer> VENUE_PRICE = new ArrayList<>(Arrays.asList(
            50_000, 1_50_000, 4_00_000, 6_00_000
    ));

    // Static pricing (unchanged for brevity, but could also be dynamic)
    static final String[] CATERING_NAMES = {"Basic 3-course meal", "Premium 5-course meal", "Luxury multi-cuisine buffet"};
    static final int[] CATERING_ADULT_PRICE = {300, 500, 1000};

    static final String[] DECOR_NAMES = {"Classic Floral", "Royal Theme", "Modern Minimalist", "Fairy-Light Garden"};
    static final int[] DECOR_PRICE = {25_000, 75_000, 60_000, 90_000};

    static final String[] PHOTO_NAMES = {"Standard (1 photographer)", "Premium (2 photographers + video)", "Cinematic (crew + drone)"};
    static final int[] PHOTO_PRICE = {20_000, 60_000, 1_20_000};

    static final String[] ADDON_NAMES = {"DJ (Full Night)", "Makeup Artist", "Transport (10 vehicles)", "Gift Hampers (per guest)"};
    static final int[] ADDON_PRICE = {25_000, 15_000, 20_000, 500};

    // =========================================================
    //  MODELS
    // =========================================================
    static class Customer {
        String name;
        String mobile;
        String email;
        String password;

        Customer(String name, String mobile, String email, String password) {
            this.name = name;
            this.mobile = mobile;
            this.email = email;
            this.password = password;
        }
    }

    static class Event {
        String eventType;
        String date;
        int venueIdx = -1;
        int cateringIdx = -1;
        int decorIdx = -1;
        int photoIdx = -1;
        int adultCount = 0;
        int kidCount = 0;

        Event(String eventType) { this.eventType = eventType; }

        boolean isComplete() {
            return venueIdx >= 0 && cateringIdx >= 0 && decorIdx >= 0
                    && photoIdx >= 0 && adultCount > 0 && date != null;
        }

        int subtotal() {
            int catering = CATERING_ADULT_PRICE[cateringIdx] * adultCount
                    + (CATERING_ADULT_PRICE[cateringIdx] / 2) * kidCount;
            return catering + VENUE_PRICE.get(venueIdx) + DECOR_PRICE[decorIdx] + PHOTO_PRICE[photoIdx];
        }
    }

    static class Booking {
        private static int counter = 1;

        String bookingId;
        String customerName;
        String email;
        String mobile;
        List<Event> events = new ArrayList<>();
        boolean[] addons = new boolean[ADDON_NAMES.length];
        String couponCode = null;
        int discountPct = 0;
        String status = "Confirmed";

        Booking(String name, String email, String mobile) {
            this.bookingId = "WMS-" + String.format("%04d", counter++);
            this.customerName = name;
            this.email = email;
            this.mobile = mobile;
        }

        int eventsSubtotal() { return events.stream().mapToInt(Event::subtotal).sum(); }
        int totalGuests() { return events.stream().mapToInt(e -> e.adultCount + e.kidCount).sum(); }

        int addonsSubtotal() {
            int sum = 0;
            for (int i = 0; i < ADDON_NAMES.length; i++) {
                if (!addons[i]) continue;
                sum += (i == 3) ? ADDON_PRICE[i] * totalGuests() : ADDON_PRICE[i];
            }
            return sum;
        }

        double grandTotal() {
            int raw = eventsSubtotal() + addonsSubtotal();
            double afterCoupon = raw * (1.0 - discountPct / 100.0);
            return afterCoupon + (afterCoupon * 0.18);
        }
    }

    // =========================================================
    //  CUSTOMER SESSION
    // =========================================================
    private Customer loggedInUser = null;
    private final Scanner sc = new Scanner(System.in);

    public void start() {
        boolean running = true;
        while (running) {
            printHeader("Wedding Management System v2.0");
            System.out.println("  1. Sign Up");
            System.out.println("  2. Login");
            System.out.println("  3. Admin Panel");
            System.out.println("  4. Exit");
            System.out.print("Select: ");

            int choice = readInt();
            switch (choice) {
                case 1: signupFlow(); break;
                case 2: loginFlow(); break;
                case 3: adminPanel(sc); break;
                case 4: System.out.println("Goodbye!"); running = false; break;
                default: System.out.println("  -> Invalid choice.");
            }
        }
    }

    private void signupFlow() {
        printDivider("Create Account");
        String name = readValidName();
        String mobile = readValidMobile();
        String email = readValidEmail();

        if (USERS.containsKey(email)) {
            System.out.println("  -> Account already exists with this email!");
            return;
        }

        String password = readValidPassword();
        USERS.put(email, new Customer(name, mobile, email, password));
        System.out.println("\nAccount created successfully for " + name + "! You can now log in.");
    }

    private void loginFlow() {
        printDivider("Login");
        System.out.println("Type 'forgot' as your email if you need to reset your password.");
        System.out.print("Email (or 'forgot'): ");
        String email = sc.nextLine().trim().toLowerCase();
        
        if (email.equals("forgot")) {
            forgotPasswordFlow();
            return;
        }

        if (!USERS.containsKey(email)) {
            System.out.println("  -> No account found. Please sign up.");
            return;
        }
        System.out.print("Password: ");
        String pass = sc.nextLine();

        Customer c = USERS.get(email);
        if (c.password.equals(pass)) {
            loggedInUser = c;
            System.out.println("Login successful. Welcome, " + c.name + "!");
            customerMenu();
        } else {
            System.out.println("  -> Incorrect password.");
        }
    }

    private void forgotPasswordFlow() {
        printDivider("Forgot Password");
        System.out.print("Registered email: ");
        String email = sc.nextLine().trim().toLowerCase();
        if (!USERS.containsKey(email)) {
            System.out.println("  -> Email not found.");
            return;
        }
        Customer c = USERS.get(email);
        System.out.print("Registered mobile: ");
        if (!sc.nextLine().trim().equals(c.mobile)) {
            System.out.println("  -> Mobile mismatch.");
            return;
        }
        System.out.println("Verification successful.");
        c.password = readValidPassword();
        System.out.println("Password reset successfully.");
    }

    private void customerMenu() {
        boolean running = true;
        while (running) {
            printDivider("Customer Dashboard - " + loggedInUser.name);
            System.out.println("  1. Create new booking");
            System.out.println("  2. View my bookings");
            System.out.println("  3. Cancel a booking");
            System.out.println("  4. Join Venue Waitlist");
            System.out.println("  5. Logout");
            System.out.print("Choice: ");

            switch (readInt()) {
                case 1: createBooking(); break;
                case 2: viewMyBookings(); break;
                case 3: cancelBooking(loggedInUser.email, false); break;
                case 4: joinWaitlistFlow(); break;
                case 5: loggedInUser = null; running = false; System.out.println("Logged out."); break;
                default: System.out.println("  -> Invalid choice.");
            }
        }
    }

    private void createBooking() {
        Booking booking = new Booking(loggedInUser.name, loggedInUser.email, loggedInUser.mobile);
        printDivider("Step 1 : Select Events");
        String[] eventTypes = {"Sangeet", "Mehendi", "Reception", "Wedding"};
        List<Event> events = new ArrayList<>();
        for (String t : eventTypes) {
            System.out.print("Include " + t + "? (y/n): ");
            String ans = sc.nextLine().trim().toLowerCase();
            if (ans.equals("y") || ans.equals("yes")) events.add(new Event(t));
        }
        if (events.isEmpty()) { System.out.println("No events selected. Booking cancelled."); return; }

        for (Event ev : events) {
            printDivider("Configuring : " + ev.eventType);
            ev.date = pickDate(ev.eventType);
            ev.venueIdx = pickVenue(ev.date);
            if (ev.venueIdx < 0) {
                System.out.println("  -> Skipping " + ev.eventType + " (no venue selected)."); continue;
            }

            while (true) {
                System.out.print("Adult guests (1-2000): ");
                int a = readInt();
                if (a >= 1 && a <= 2000) { ev.adultCount = a; break; }
                System.out.println("  -> Invalid count.");
            }
            while (true) {
                System.out.print("Kid guests  (0-1000): ");
                int k = readInt();
                if (k >= 0 && k <= 1000) { ev.kidCount = k; break; }
                System.out.println("  -> Invalid count.");
            }

            System.out.println("\nSelect Catering:");
            for (int i = 0; i < CATERING_NAMES.length; i++) {
                System.out.printf("  %d. %-35s Adult: Rs.%-6d Kids: Rs.%d%n",
                        i + 1, CATERING_NAMES[i], CATERING_ADULT_PRICE[i], CATERING_ADULT_PRICE[i] / 2);
            }
            ev.cateringIdx = pickIndex(CATERING_NAMES.length) - 1;
            ev.decorIdx = pickFromList("Decoration", DECOR_NAMES, DECOR_PRICE, "flat");
            ev.photoIdx = pickFromList("Photography", PHOTO_NAMES, PHOTO_PRICE, "flat");

            VENUE_DATE_REGISTRY.put(ev.venueIdx + "|" + ev.date, booking.bookingId);
        }

        booking.events = events.stream().filter(Event::isComplete).collect(Collectors.toList());
        if (booking.events.isEmpty()) {
            System.out.println("No complete events. Booking cancelled."); return;
        }

        printDivider("Step 3 : Optional Add-ons");
        int totalGuests = booking.totalGuests();
        for (int i = 0; i < ADDON_NAMES.length; i++) {
            System.out.print("  Add " + ADDON_NAMES[i] + "? (y/n): ");
            String ans = sc.nextLine().trim().toLowerCase();
            booking.addons[i] = (ans.equals("y") || ans.equals("yes"));
        }

        printDivider("Step 4 : Coupon Code");
        System.out.println("  Available: " + String.join(", ", COUPONS.keySet()));
        System.out.print("  Enter coupon code (or press Enter to skip): ");
        String code = sc.nextLine().trim().toUpperCase();
        if (!code.isEmpty() && COUPONS.containsKey(code)) {
            booking.couponCode = code;
            booking.discountPct = COUPONS.get(code);
            System.out.println("  -> Coupon applied: " + booking.discountPct + "% discount.");
        }

        printInvoice(booking);
        System.out.print("\nConfirm booking? (y/n): ");
        String confirm = sc.nextLine().trim().toLowerCase();
        if (confirm.equals("y") || confirm.equals("yes")) {
            ALL_BOOKINGS.add(booking);
            System.out.println("\nBooking CONFIRMED! ID: " + booking.bookingId);
        } else {
            for (Event ev : booking.events) VENUE_DATE_REGISTRY.remove(ev.venueIdx + "|" + ev.date);
            System.out.println("Booking cancelled. Venue slots released.");
        }
    }

    private void viewMyBookings() {
        List<Booking> mine = ALL_BOOKINGS.stream()
                .filter(b -> b.email.equals(loggedInUser.email)).collect(Collectors.toList());
        if (mine.isEmpty()) { System.out.println("You have no bookings yet."); return; }

        System.out.println("\n--- Your Bookings ---");
        System.out.printf("%-10s  %-22s  %15s  %-15s%n", "ID", "Events", "Grand Total", "Status");
        System.out.println("-----------------------------------------------------------------");
        for (Booking b : mine) {
            String evNames = b.events.stream().map(e -> e.eventType).collect(Collectors.joining(", "));
            System.out.printf("%-10s  %-22s  Rs.%,12.2f  %-15s%n", b.bookingId, evNames, b.grandTotal(), b.status);
        }
        System.out.print("\nEnter Booking ID for full invoice (or 0 to go back): ");
        String id = sc.nextLine().trim().toUpperCase();
        if (!id.equals("0")) {
            Optional<Booking> opt = mine.stream().filter(b -> b.bookingId.equals(id)).findFirst();
            if (opt.isPresent()) {
                this.printInvoice(opt.get());
            } else {
                System.out.println("  -> Not found.");
            }
        }
    }

    private void cancelBooking(String targetEmail, boolean isAdmin) {
        System.out.print("Enter Booking ID to cancel: ");
        String id = sc.nextLine().trim().toUpperCase();
        Booking target = ALL_BOOKINGS.stream()
                .filter(b -> b.bookingId.equals(id) && (isAdmin || b.email.equals(targetEmail)))
                .findFirst().orElse(null);

        if (target == null) {
            System.out.println("  -> Booking not found or access denied.");
            return;
        }

        ALL_BOOKINGS.remove(target);
        for (Event ev : target.events) {
            String key = ev.venueIdx + "|" + ev.date;
            VENUE_DATE_REGISTRY.remove(key);
            
            // Check waitlist
            if (WAITLIST.containsKey(key) && !WAITLIST.get(key).isEmpty()) {
                String nextInLine = WAITLIST.get(key).remove(0);
                System.out.println("  [SYSTEM NOTIFICATION] Venue " + VENUE_NAMES.get(ev.venueIdx) + 
                        " on " + ev.date + " is now available! Waitlisted user notified: " + nextInLine);
            }
        }
        System.out.println("Booking " + id + " cancelled successfully. Venue dates released.");
    }

    private void joinWaitlistFlow() {
        printDivider("Join Venue Waitlist");
        String date = pickDate("Waitlist");
        System.out.println("Select Venue:");
        for (int i = 0; i < VENUE_NAMES.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, VENUE_NAMES.get(i));
        }
        int vIdx = pickIndex(VENUE_NAMES.size()) - 1;
        String key = vIdx + "|" + date;
        
        WAITLIST.putIfAbsent(key, new ArrayList<>());
        if (!WAITLIST.get(key).contains(loggedInUser.email)) {
            WAITLIST.get(key).add(loggedInUser.email);
            System.out.println("You have been added to the waitlist for " + VENUE_NAMES.get(vIdx) + " on " + date);
        } else {
            System.out.println("You are already on the waitlist for this venue and date.");
        }
    }

    // =========================================================
    //  ADMIN PANEL
    // =========================================================
    private void adminPanel(Scanner sc) {
        printDivider("ADMIN LOGIN");
        System.out.print("Email: ");
        if (!sc.nextLine().trim().equals(ADMIN_EMAIL)) { System.out.println("  -> Denied."); return; }
        System.out.print("Password: ");
        if (!sc.nextLine().equals(ADMIN_PASSWORD)) { System.out.println("  -> Denied."); return; }

        boolean running = true;
        while (running) {
            printDivider("Admin Master Console");
            System.out.println("  1. View All Bookings");
            System.out.println("  2. Change Booking Status");
            System.out.println("  3. Force Cancel a Booking");
            System.out.println("  4. View Venue Calendar");
            System.out.println("  5. Manage Venues");
            System.out.println("  6. Manage Coupons");
            System.out.println("  7. View Customer Directory");
            System.out.println("  8. Advanced Analytics Dashboard");
            System.out.println("  9. Export Financial Report");
            System.out.println("  10. Logout");
            System.out.print("Choice: ");

            switch (readInt()) {
                case 1: adminAllBookings(); break;
                case 2: adminChangeStatus(); break;
                case 3: cancelBooking(null, true); break;
                case 4: adminCalendar(); break;
                case 5: adminManageVenues(); break;
                case 6: adminManageCoupons(); break;
                case 7: adminViewCustomers(); break;
                case 8: adminAnalytics(); break;
                case 9: adminExportFinancials(); break;
                case 10: running = false; break;
                default: System.out.println("  -> Invalid.");
            }
        }
    }

    private void adminAllBookings() {
        if (ALL_BOOKINGS.isEmpty()) { System.out.println("No bookings."); return; }
        System.out.printf("%n%-10s %-15s %-20s %-12s %12s%n", "ID", "Customer", "Events", "Status", "Total");
        for (Booking b : ALL_BOOKINGS) {
            String evs = b.events.stream().map(e -> e.eventType).collect(Collectors.joining("+"));
            System.out.printf("%-10s %-15s %-20s %-12s %,12.2f%n", 
                b.bookingId, b.customerName, evs, b.status, b.grandTotal());
        }
    }

    private void adminChangeStatus() {
        System.out.print("Enter Booking ID: ");
        String id = sc.nextLine().trim().toUpperCase();
        Booking b = ALL_BOOKINGS.stream().filter(bk -> bk.bookingId.equals(id)).findFirst().orElse(null);
        if (b == null) { System.out.println("Not found."); return; }

        System.out.println("Current status: " + b.status);
        System.out.println("Select new status: 1. Confirmed  2. Payment Pending  3. In Progress  4. Completed");
        int c = readInt();
        if (c == 1) b.status = "Confirmed";
        else if (c == 2) b.status = "Payment Pending";
        else if (c == 3) b.status = "In Progress";
        else if (c == 4) b.status = "Completed";
        System.out.println("Status updated to: " + b.status);
    }

    private void adminCalendar() {
        if (VENUE_DATE_REGISTRY.isEmpty()) { System.out.println("No booked venues."); return; }
        List<Map.Entry<String, String>> entries = new ArrayList<>(VENUE_DATE_REGISTRY.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().split("\\|")[1]));
        System.out.printf("%n%-25s %-12s %-10s%n", "Venue", "Date", "Booking ID");
        for (Map.Entry<String, String> e : entries) {
            String[] parts = e.getKey().split("\\|");
            int vIdx = Integer.parseInt(parts[0]);
            System.out.printf("%-25s %-12s %-10s%n", VENUE_NAMES.get(vIdx), parts[1], e.getValue());
        }
    }

    private void adminManageVenues() {
        System.out.println("\n--- Manage Venues ---");
        for (int i = 0; i < VENUE_NAMES.size(); i++) {
            System.out.printf("%d. %-25s Rs.%,d%n", i + 1, VENUE_NAMES.get(i), VENUE_PRICE.get(i));
        }
        System.out.println("\nOptions: 1. Add Venue  2. Remove Venue  3. Go Back");
        int c = readInt();
        if (c == 1) {
            System.out.print("Venue Name: ");
            String n = sc.nextLine().trim();
            System.out.print("Price: ");
            int p = readInt();
            VENUE_NAMES.add(n);
            VENUE_PRICE.add(p);
            System.out.println("Venue added!");
        } else if (c == 2) {
            System.out.print("Enter number to remove: ");
            int idx = readInt() - 1;
            if (idx >= 0 && idx < VENUE_NAMES.size()) {
                VENUE_NAMES.remove(idx);
                VENUE_PRICE.remove(idx);
                System.out.println("Venue removed.");
            }
        }
    }

    private void adminManageCoupons() {
        System.out.println("\n--- Current Coupons ---");
        COUPONS.forEach((k, v) -> System.out.println(k + " : " + v + "%"));
        System.out.println("\nOptions: 1. Add Coupon  2. Remove Coupon  3. Go Back");
        int c = readInt();
        if (c == 1) {
            System.out.print("Code (e.g. SUMMER50): ");
            String code = sc.nextLine().trim().toUpperCase();
            System.out.print("Discount % (1-100): ");
            int pct = readInt();
            COUPONS.put(code, pct);
            System.out.println("Added.");
        } else if (c == 2) {
            System.out.print("Code to remove: ");
            String code = sc.nextLine().trim().toUpperCase();
            COUPONS.remove(code);
            System.out.println("Removed.");
        }
    }

    private void adminViewCustomers() {
        System.out.println("\n--- Customer Directory ---");
        System.out.printf("%-20s %-25s %-15s%n", "Name", "Email", "Mobile");
        System.out.println("------------------------------------------------------------");
        for (Customer c : USERS.values()) {
            System.out.printf("%-20s %-25s %-15s%n", c.name, c.email, c.mobile);
        }
        System.out.println("Total Registered Users: " + USERS.size());
    }

    private void adminAnalytics() {
        System.out.println("\n--- Advanced Analytics ---");
        double totalRev = ALL_BOOKINGS.stream().mapToDouble(Booking::grandTotal).sum();
        System.out.printf("Total Revenue : Rs.%,.2f%n", totalRev);
        System.out.println("Total Bookings: " + ALL_BOOKINGS.size());

        if (ALL_BOOKINGS.isEmpty()) return;

        // Top Venue
        Map<Integer, Long> venueCounts = ALL_BOOKINGS.stream()
                .flatMap(b -> b.events.stream())
                .collect(Collectors.groupingBy(e -> e.venueIdx, Collectors.counting()));
        venueCounts.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> 
            System.out.println("Most Popular Venue : " + VENUE_NAMES.get(e.getKey()) + " (" + e.getValue() + " bookings)")
        );

        // Peak Month
        Map<String, Long> monthCounts = ALL_BOOKINGS.stream()
                .flatMap(b -> b.events.stream())
                .collect(Collectors.groupingBy(e -> {
                    String[] parts = e.date.split("-");
                    return parts.length == 3 ? parts[1] + "-" + parts[2] : "Unknown";
                }, Collectors.counting()));
        monthCounts.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> 
            System.out.println("Peak Wedding Month : " + e.getKey() + " (" + e.getValue() + " events)")
        );

        // Top Coupon
        Map<String, Long> couponCounts = ALL_BOOKINGS.stream()
                .filter(b -> b.couponCode != null)
                .collect(Collectors.groupingBy(b -> b.couponCode, Collectors.counting()));
        couponCounts.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> 
            System.out.println("Most Used Coupon   : " + e.getKey() + " (" + e.getValue() + " uses)")
        );
    }

    private void adminExportFinancials() {
        try (FileWriter writer = new FileWriter("financial_report.txt")) {
            writer.write("WEDDING MANAGEMENT SYSTEM - FINANCIAL REPORT\n");
            writer.write("============================================\n\n");
            double total = 0;
            for (Booking b : ALL_BOOKINGS) {
                writer.write(String.format("Booking ID: %s | Customer: %s | Revenue: Rs.%,.2f | Status: %s\n",
                        b.bookingId, b.customerName, b.grandTotal(), b.status));
                total += b.grandTotal();
            }
            writer.write("\n============================================\n");
            writer.write(String.format("GRAND TOTAL REVENUE: Rs.%,.2f\n", total));
            System.out.println("Report exported to 'financial_report.txt' successfully.");
        } catch (IOException e) {
            System.out.println("Error writing file: " + e.getMessage());
        }
    }

    // =========================================================
    //  UTILITIES
    // =========================================================
    private String pickDate(String label) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd-MM-yyyy");
        fmt.setLenient(false);
        while (true) {
            System.out.print(label + " date (dd-MM-yyyy): ");
            String input = sc.nextLine().trim();
            try {
                Date d = fmt.parse(input);
                if (d.before(new Date())) { System.out.println("  -> Future dates only."); continue; }
                return input;
            } catch (Exception e) { System.out.println("  -> Invalid format."); }
        }
    }

    private int pickVenue(String date) {
        System.out.println("\nAvailable venues on " + date + ":");
        boolean anyFree = false;
        for (int i = 0; i < VENUE_NAMES.size(); i++) {
            boolean booked = VENUE_DATE_REGISTRY.containsKey(i + "|" + date);
            System.out.printf("  %d. %-25s Rs.%,d flat [%s]%n", i + 1, VENUE_NAMES.get(i), VENUE_PRICE.get(i), booked ? "BOOKED" : "Free");
            if (!booked) anyFree = true;
        }
        if (!anyFree) { System.out.println("  All booked."); return -1; }
        System.out.println("  0. Skip event");
        while (true) {
            int c = readInt();
            if (c == 0) return -1;
            if (c > 0 && c <= VENUE_NAMES.size()) {
                if (VENUE_DATE_REGISTRY.containsKey((c - 1) + "|" + date)) {
                    System.out.println("  -> Already booked."); continue;
                }
                return c - 1;
            }
            System.out.print("  -> Invalid. Select: ");
        }
    }

    private int pickFromList(String label, String[] names, int[] prices, String mode) {
        System.out.println("\nSelect " + label + ":");
        for (int i = 0; i < names.length; i++) System.out.printf("  %d. %-35s Rs.%,d %s%n", i + 1, names[i], prices[i], mode);
        return pickIndex(names.length) - 1;
    }

    private int pickIndex(int max) {
        while (true) {
            System.out.print("Choice (1-" + max + "): ");
            int c = readInt();
            if (c >= 1 && c <= max) return c;
            System.out.println("  -> Enter a number between 1 and " + max + ".");
        }
    }

    private int readInt() {
        while (true) {
            try { return Integer.parseInt(sc.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.print("  -> Numbers only: "); }
        }
    }

    private String readValidName() {
        while (true) {
            System.out.print("Full name: ");
            String n = sc.nextLine().trim();
            if (!n.isEmpty() && n.chars().allMatch(c -> Character.isLetter(c) || c == ' ')) return n;
            System.out.println("  -> Letters and spaces only.");
        }
    }

    private String readValidMobile() {
        while (true) {
            System.out.print("10-digit mobile: ");
            String m = sc.nextLine().trim();
            if (m.length() == 10 && m.charAt(0) != '0' && m.chars().allMatch(Character::isDigit)) return m;
            System.out.println("  -> Invalid mobile.");
        }
    }

    private String readValidEmail() {
        while (true) {
            System.out.print("Email (@gmail.com): ");
            String e = sc.nextLine().trim().toLowerCase();
            if (e.endsWith("@gmail.com") && e.length() > 10) return e;
            System.out.println("  -> Invalid email.");
        }
    }

    private String readValidPassword() {
        while (true) {
            System.out.print("Password (8-15 chars, no spaces): ");
            String p = sc.nextLine();
            if (p.length() >= 8 && p.length() <= 15 && !p.contains(" ")) return p;
            System.out.println("  -> Invalid password.");
        }
    }

    private void printInvoice(Booking b) {
        System.out.println("\n====================================================");
        System.out.println("                  INVOICE");
        System.out.println("====================================================");
        System.out.printf("Booking ID : %s%nCustomer   : %s%n", b.bookingId, b.customerName);
        System.out.println("----------------------------------------------------");
        for (Event ev : b.events) {
            System.out.printf("  [%s — %s]%n", ev.eventType.toUpperCase(), ev.date);
            System.out.printf("  Venue    : %-25s Rs.%,d%n", VENUE_NAMES.get(ev.venueIdx), VENUE_PRICE.get(ev.venueIdx));
            System.out.printf("  Subtotal :                           Rs.%,d%n%n", ev.subtotal());
        }
        int raw = b.eventsSubtotal() + b.addonsSubtotal();
        double afterDiscount = raw * (1.0 - b.discountPct / 100.0);
        double gst = afterDiscount * 0.18;
        System.out.println("----------------------------------------------------");
        System.out.printf("Subtotal                             Rs.%,10d%n", raw);
        System.out.printf("Discount (%d%%)                       -Rs.%,10.2f%n", b.discountPct, raw - afterDiscount);
        System.out.printf("GST (18%%)                            Rs.%,10.2f%n", gst);
        System.out.println("====================================================");
        System.out.printf("GRAND TOTAL                          Rs.%,10.2f%n", b.grandTotal());
        System.out.println("====================================================\n");
    }

    private static void printHeader(String t) { 
        System.out.println("\n==================================================\n  " + t + "\n=================================================="); 
    }
    private static void printDivider(String t) { System.out.println("\n--- " + t + " ---"); }

    public static void main(String[] args) {
        new WeddingManagement().start();
    }
}
