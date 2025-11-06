// File: ConferenceRoomBookingApp.java
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Simple in-memory Conference Room Booking console app.
 * Single-file example for demonstration.
 *
 * Compile: javac ConferenceRoomBookingApp.java
 * Run:     java ConferenceRoomBookingApp
 */
public class ConferenceRoomBookingApp {

    // ------- Domain models -------
    static class Room {
        final String id;
        final String name;
        final int capacity;
        final Set<String> equipment;
        final String location;

        Room(String id, String name, int capacity, Set<String> equipment, String location) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
            this.equipment = new HashSet<>(equipment);
            this.location = location;
        }

        public String toString() {
            return String.format("Room[id=%s,name=%s,cap=%d,eq=%s,loc=%s]", id, name, capacity, equipment, location);
        }
    }

    static class User {
        final String id;
        final String name;

        User(String id, String name) { this.id = id; this.name = name; }
        public String toString() { return String.format("User[id=%s,name=%s]", id, name); }
    }

    static class Booking {
        final String id;
        final String roomId;
        final String userId;
        final LocalDateTime start;
        final LocalDateTime end;
        final LocalDateTime createdAt;

        Booking(String id, String roomId, String userId, LocalDateTime start, LocalDateTime end) {
            this.id = id;
            this.roomId = roomId;
            this.userId = userId;
            this.start = start;
            this.end = end;
            this.createdAt = LocalDateTime.now();
        }

        boolean overlaps(LocalDateTime s, LocalDateTime e) {
            // overlap if start < existing.end && existing.start < end
            return start.isBefore(e) && s.isBefore(end);
        }

        public String toString() {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            return String.format("Booking[id=%s,room=%s,user=%s,start=%s,end=%s,created=%s]",
                    id, roomId, userId, start.format(fmt), end.format(fmt), createdAt.format(fmt));
        }
    }

    // ------- Services / Repositories (in-memory) -------
    static class RoomService {
        private final Map<String, Room> rooms = new HashMap<>();

        Room addRoom(String name, int capacity, Set<String> equipment, String location) {
            String id = "R" + UUID.randomUUID().toString().substring(0, 8);
            Room r = new Room(id, name, capacity, equipment, location);
            rooms.put(id, r);
            return r;
        }

        Room getRoom(String id) { return rooms.get(id); }
        List<Room> listAll() { return new ArrayList<>(rooms.values()); }

        /**
         * Search rooms that meet capacity & equipment and are available in the given time.
         * Delegates booking checks to bookingService.
         */
        List<Room> searchAvailable(LocalDateTime start, LocalDateTime end, int minCapacity, Set<String> requiredEquipment, BookingService bookingService) {
            return rooms.values().stream()
                    .filter(r -> r.capacity >= minCapacity)
                    .filter(r -> r.equipment.containsAll(requiredEquipment))
                    .filter(r -> bookingService.isRoomAvailable(r.id, start, end))
                    .sorted(Comparator.comparingInt(r -> r.capacity))
                    .collect(Collectors.toList());
        }
    }

    static class BookingService {
        private final Map<String, Booking> bookings = new HashMap<>();
        private final Map<String, List<Booking>> bookingsByRoom = new HashMap<>();
        private final Map<String, List<Booking>> bookingsByUser = new HashMap<>();

        // Business hours
        private final LocalTime workStart = LocalTime.of(8, 0);
        private final LocalTime workEnd = LocalTime.of(18, 0);

        /** Create booking -- synchronized to prevent race conditions in this simple example */
        synchronized Result createBooking(String userId, String roomId, LocalDateTime start, LocalDateTime end, RoomService roomService) {
            // validations
            if (start == null || end == null || !start.isBefore(end)) {
                return Result.failure("Invalid time range (start must be before end).");
            }
            if (start.isBefore(LocalDateTime.now())) {
                return Result.failure("Cannot book in the past.");
            }
            if (!isWithinBusinessHours(start.toLocalTime(), end.toLocalTime())) {
                return Result.failure("Booking is outside business hours: " + workStart + " - " + workEnd);
            }
            Room room = roomService.getRoom(roomId);
            if (room == null) {
                return Result.failure("Room id not found: " + roomId);
            }

            // check overlap on existing bookings for this room
            List<Booking> existing = bookingsByRoom.getOrDefault(roomId, Collections.emptyList());
            for (Booking b : existing) {
                if (b.overlaps(start, end)) {
                    return Result.failure("Room already booked in overlapping time by bookingId=" + b.id);
                }
            }

            // ok create
            String id = "B" + UUID.randomUUID().toString().substring(0, 8);
            Booking booking = new Booking(id, roomId, userId, start, end);
            bookings.put(id, booking);
            bookingsByRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(booking);
            bookingsByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(booking);
            return Result.success(booking);
        }

        synchronized Result cancelBooking(String bookingId, String requestingUserId) {
            Booking b = bookings.get(bookingId);
            if (b == null) return Result.failure("Booking not found: " + bookingId);
            // only owner can cancel in this simple demo
            if (!b.userId.equals(requestingUserId)) {
                return Result.failure("Only the user who created the booking can cancel it.");
            }
            bookings.remove(bookingId);
            bookingsByRoom.getOrDefault(b.roomId, new ArrayList<>()).removeIf(x -> x.id.equals(bookingId));
            bookingsByUser.getOrDefault(b.userId, new ArrayList<>()).removeIf(x -> x.id.equals(bookingId));
            return Result.success("Cancelled booking " + bookingId);
        }

        List<Booking> listBookingsForUser(String userId) {
            return new ArrayList<>(bookingsByUser.getOrDefault(userId, Collections.emptyList()));
        }

        List<Booking> listBookingsForRoom(String roomId) {
            return new ArrayList<>(bookingsByRoom.getOrDefault(roomId, Collections.emptyList()));
        }

        boolean isRoomAvailable(String roomId, LocalDateTime start, LocalDateTime end) {
            List<Booking> existing = bookingsByRoom.getOrDefault(roomId, Collections.emptyList());
            for (Booking b : existing) {
                if (b.overlaps(start, end)) return false;
            }
            return true;
        }

        private boolean isWithinBusinessHours(LocalTime s, LocalTime e) {
            // booking must start and end inside the same business day window
            return !s.isBefore(workStart) && !e.isAfter(workEnd);
        }
    }

    // simple result wrapper
    static class Result {
        final boolean ok;
        final String message;
        final Booking booking;

        private Result(boolean ok, String message, Booking booking) {
            this.ok = ok; this.message = message; this.booking = booking;
        }

        static Result success(Booking b) { return new Result(true, "OK", b); }
        static Result success(String msg) { return new Result(true, msg, null); }
        static Result failure(String msg) { return new Result(false, msg, null); }
    }

    // ------- Demo driver / CLI -------
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        RoomService roomService = new RoomService();
        BookingService bookingService = new BookingService();

        // create demo users
        User alice = new User("U1", "Alice");
        User bob = new User("U2", "Bob");

        // create demo rooms
        Room r1 = roomService.addRoom("Orchid", 6, Set.of("PROJECTOR", "WHITEBOARD"), "Floor 1");
        Room r2 = roomService.addRoom("Lotus", 12, Set.of("VC", "PROJECTOR"), "Floor 2");
        Room r3 = roomService.addRoom("Iris", 4, Set.of("WHITEBOARD"), "Floor 1");

        System.out.println("Welcome to Conference Room Booking Demo");
        System.out.println("Demo users: " + alice + ", " + bob);
        System.out.println("Demo rooms:");
        roomService.listAll().forEach(System.out::println);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        outer: while (true) {
            System.out.println("\nChoose action:");
            System.out.println("1) List rooms");
            System.out.println("2) Search available rooms");
            System.out.println("3) Create booking");
            System.out.println("4) Cancel booking");
            System.out.println("5) List my bookings");
            System.out.println("6) Demo: simulate overlapping booking attempt");
            System.out.println("0) Exit");
            System.out.print("> ");
            String opt = scanner.nextLine().trim();
            try {
                switch (opt) {
                    case "1" -> {
                        roomService.listAll().forEach(System.out::println);
                    }
                    case "2" -> {
                        System.out.print("Enter start (yyyy-MM-dd HH:mm): ");
                        LocalDateTime start = LocalDateTime.parse(scanner.nextLine().trim(), fmt);
                        System.out.print("Enter end   (yyyy-MM-dd HH:mm): ");
                        LocalDateTime end = LocalDateTime.parse(scanner.nextLine().trim(), fmt);
                        System.out.print("Min capacity: ");
                        int cap = Integer.parseInt(scanner.nextLine().trim());
                        System.out.print("Required equipment (comma sep, empty for none): ");
                        String eqs = scanner.nextLine().trim();
                        Set<String> req = eqs.isBlank() ? Set.of() :
                                Arrays.stream(eqs.split(",")).map(String::trim).collect(Collectors.toSet());
                        var avail = roomService.searchAvailable(start, end, cap, req, bookingService);
                        System.out.println("Available rooms:");
                        avail.forEach(System.out::println);
                    }
                    case "3" -> {
                        System.out.print("UserId (U1/U2): ");
                        String userId = scanner.nextLine().trim();
                        System.out.print("RoomId: ");
                        String roomId = scanner.nextLine().trim();
                        System.out.print("Start (yyyy-MM-dd HH:mm): ");
                        LocalDateTime start = LocalDateTime.parse(scanner.nextLine().trim(), fmt);
                        System.out.print("End   (yyyy-MM-dd HH:mm): ");
                        LocalDateTime end = LocalDateTime.parse(scanner.nextLine().trim(), fmt);
                        Result res = bookingService.createBooking(userId, roomId, start, end, roomService);
                        if (res.ok && res.booking != null) {
                            System.out.println("Booking created: " + res.booking);
                        } else {
                            System.out.println("Booking failed: " + res.message);
                        }
                    }
                    case "4" -> {
                        System.out.print("UserId (U1/U2): ");
                        String u = scanner.nextLine().trim();
                        System.out.print("BookingId: ");
                        String bid = scanner.nextLine().trim();
                        Result r = bookingService.cancelBooking(bid, u);
                        System.out.println(r.ok ? "Cancelled." : "Cancel failed: " + r.message);
                    }
                    case "5" -> {
                        System.out.print("UserId (U1/U2): ");
                        String u = scanner.nextLine().trim();
                        var list = bookingService.listBookingsForUser(u);
                        if (list.isEmpty()) System.out.println("No bookings.");
                        else list.forEach(System.out::println);
                    }
                    case "6" -> {
                        // demo concurrency: two threads attempt to book the same room same slot
                        System.out.println("Demo: two threads try to book same room / same slot.");
                        LocalDateTime start = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
                        LocalDateTime end = start.plusHours(1);
                        Runnable t1 = () -> {
                            Result r = bookingService.createBooking(alice.id, r1.id, start, end, roomService);
                            System.out.println("[T1] " + (r.ok ? "Success " + r.booking.id : "Fail: " + r.message));
                        };
                        Runnable t2 = () -> {
                            Result r = bookingService.createBooking(bob.id, r1.id, start, end, roomService);
                            System.out.println("[T2] " + (r.ok ? "Success " + r.booking.id : "Fail: " + r.message));
                        };
                        Thread th1 = new Thread(t1);
                        Thread th2 = new Thread(t2);
                        th1.start(); th2.start();
                        th1.join(); th2.join();
                        System.out.println("Room bookings for demo:");
                        bookingService.listBookingsForRoom(r1.id).forEach(System.out::println);
                    }
                    case "0" -> { break outer; }
                    default -> System.out.println("Unknown option");
                }
            } catch (Exception ex) {
                System.out.println("Error: " + ex.getMessage());
            }
        }

        System.out.println("Goodbye.");
    }
}
