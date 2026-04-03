import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;

// ─────────────────────────────────────────────
//  ENUMS
// ─────────────────────────────────────────────

enum VehicleType { CAR, TRUCK, MOTORBIKE, VAN }

enum SpotType { COMPACT, LARGE, HANDICAPPED, MOTORBIKE }

enum ParkingTicketStatus { ACTIVE, PAID, LOST }

enum PaymentStatus { PENDING, COMPLETED, FAILED }

// ─────────────────────────────────────────────
//  VEHICLE HIERARCHY
// ─────────────────────────────────────────────

abstract class Vehicle {
    protected String licensePlate;
    protected String color;
    protected VehicleType type;

    public Vehicle(String licensePlate, String color, VehicleType type) {
        this.licensePlate = licensePlate;
        this.color = color;
        this.type = type;
    }

    public VehicleType getType() { return type; }
    public String getLicensePlate() { return licensePlate; }

    @Override
    public String toString() {
        return type + " [" + licensePlate + ", " + color + "]";
    }
}

class Car extends Vehicle {
    public Car(String licensePlate, String color) {
        super(licensePlate, color, VehicleType.CAR);
    }
}

class Truck extends Vehicle {
    public Truck(String licensePlate, String color) {
        super(licensePlate, color, VehicleType.TRUCK);
    }
}

class Motorbike extends Vehicle {
    public Motorbike(String licensePlate, String color) {
        super(licensePlate, color, VehicleType.MOTORBIKE);
    }
}

class Van extends Vehicle {
    public Van(String licensePlate, String color) {
        super(licensePlate, color, VehicleType.VAN);
    }
}

// ─────────────────────────────────────────────
//  PARKING SPOT HIERARCHY
// ─────────────────────────────────────────────

abstract class ParkingSpot {
    private String spotId;
    private boolean isFree;
    private Vehicle currentVehicle;
    protected SpotType type;

    public ParkingSpot(String spotId, SpotType type) {
        this.spotId = spotId;
        this.type = type;
        this.isFree = true;
    }

    public boolean isFree() { return isFree; }
    public String getSpotId() { return spotId; }
    public SpotType getType() { return type; }

    // Each subclass decides which vehicle types are allowed
    public abstract boolean canFitVehicle(Vehicle vehicle);

    public boolean assignVehicle(Vehicle vehicle) {
        if (!isFree || !canFitVehicle(vehicle)) return false;
        this.currentVehicle = vehicle;
        this.isFree = false;
        System.out.println("  Vehicle " + vehicle + " parked at spot " + spotId);
        return true;
    }

    public void removeVehicle() {
        System.out.println("  Vehicle " + currentVehicle + " removed from spot " + spotId);
        this.currentVehicle = null;
        this.isFree = true;
    }

    public Vehicle getCurrentVehicle() { return currentVehicle; }

    @Override
    public String toString() {
        return spotId + " (" + type + ") - " + (isFree ? "FREE" : "OCCUPIED by " + currentVehicle);
    }
}

class CompactSpot extends ParkingSpot {
    public CompactSpot(String spotId) { super(spotId, SpotType.COMPACT); }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getType() == VehicleType.CAR || vehicle.getType() == VehicleType.MOTORBIKE;
    }
}

class LargeSpot extends ParkingSpot {
    public LargeSpot(String spotId) { super(spotId, SpotType.LARGE); }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return true; // Large spots fit all vehicle types
    }
}

class HandicappedSpot extends ParkingSpot {
    public HandicappedSpot(String spotId) { super(spotId, SpotType.HANDICAPPED); }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getType() == VehicleType.CAR || vehicle.getType() == VehicleType.VAN;
    }
}

class MotorbikeSpot extends ParkingSpot {
    public MotorbikeSpot(String spotId) { super(spotId, SpotType.MOTORBIKE); }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getType() == VehicleType.MOTORBIKE;
    }
}

// ─────────────────────────────────────────────
//  PARKING TICKET
// ─────────────────────────────────────────────

class ParkingTicket {
    private static int counter = 1000;
    private String ticketId;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private Vehicle vehicle;
    private ParkingSpot spot;
    private ParkingTicketStatus status;

    public ParkingTicket(Vehicle vehicle, ParkingSpot spot) {
        this.ticketId = "TKT-" + (++counter);
        this.vehicle = vehicle;
        this.spot = spot;
        this.entryTime = LocalDateTime.now();
        this.status = ParkingTicketStatus.ACTIVE;
    }

    public void checkout() {
        this.exitTime = LocalDateTime.now();
    }

    public long getDurationMinutes() {
        LocalDateTime end = (exitTime != null) ? exitTime : LocalDateTime.now();
        return Duration.between(entryTime, end).toMinutes();
    }

    public String getTicketId() { return ticketId; }
    public Vehicle getVehicle() { return vehicle; }
    public ParkingSpot getSpot() { return spot; }
    public ParkingTicketStatus getStatus() { return status; }
    public void setStatus(ParkingTicketStatus status) { this.status = status; }

    @Override
    public String toString() {
        return "[" + ticketId + "] " + vehicle + " at " + spot.getSpotId()
                + " | Entry: " + entryTime
                + " | Duration: " + getDurationMinutes() + " min"
                + " | Status: " + status;
    }
}

// ─────────────────────────────────────────────
//  PAYMENT HIERARCHY
// ─────────────────────────────────────────────

abstract class Payment {
    protected double amount;
    protected PaymentStatus status;
    protected LocalDateTime paymentTime;

    public Payment(double amount) {
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    public abstract boolean processPayment();

    public PaymentStatus getStatus() { return status; }
    public double getAmount() { return amount; }
}

class CashPayment extends Payment {
    private double amountTendered;

    public CashPayment(double amount, double amountTendered) {
        super(amount);
        this.amountTendered = amountTendered;
    }

    @Override
    public boolean processPayment() {
        if (amountTendered >= amount) {
            this.status = PaymentStatus.COMPLETED;
            this.paymentTime = LocalDateTime.now();
            double change = amountTendered - amount;
            System.out.printf("  Cash payment of $%.2f accepted. Change: $%.2f%n", amount, change);
            return true;
        }
        this.status = PaymentStatus.FAILED;
        System.out.println("  Insufficient cash tendered.");
        return false;
    }
}

class CardPayment extends Payment {
    private String cardNumber;

    public CardPayment(double amount, String cardNumber) {
        super(amount);
        this.cardNumber = cardNumber;
    }

    @Override
    public boolean processPayment() {
        // Simulate card authorization (always succeeds here)
        System.out.printf("  Card ending %s charged $%.2f%n",
                cardNumber.substring(cardNumber.length() - 4), amount);
        this.status = PaymentStatus.COMPLETED;
        this.paymentTime = LocalDateTime.now();
        return true;
    }
}

// ─────────────────────────────────────────────
//  PARKING RATE
// ─────────────────────────────────────────────

class ParkingRate {
    private double ratePerHour;   // base rate
    private double truckMultiplier = 2.0;
    private double vanMultiplier  = 1.5;

    public ParkingRate(double ratePerHour) {
        this.ratePerHour = ratePerHour;
    }

    public double calculateFee(ParkingTicket ticket) {
        long minutes = ticket.getDurationMinutes();
        // Minimum charge: 1 hour
        double hours = Math.max(1.0, minutes / 60.0);
        double base  = hours * ratePerHour;

        return switch (ticket.getVehicle().getType()) {
            case TRUCK    -> base * truckMultiplier;
            case VAN      -> base * vanMultiplier;
            default       -> base;
        };
    }
}

// ─────────────────────────────────────────────
//  ENTRANCE / EXIT PANELS
// ─────────────────────────────────────────────

class EntrancePanel {
    private String panelId;
    private ParkingLot parkingLot;

    public EntrancePanel(String panelId, ParkingLot parkingLot) {
        this.panelId = panelId;
        this.parkingLot = parkingLot;
    }

    public ParkingTicket admitVehicle(Vehicle vehicle) {
        System.out.println("[Entrance " + panelId + "] Admitting: " + vehicle);
        ParkingTicket ticket = parkingLot.parkVehicle(vehicle);
        if (ticket != null) {
            System.out.println("  Ticket issued: " + ticket.getTicketId());
        } else {
            System.out.println("  No available spot for " + vehicle.getType());
        }
        return ticket;
    }
}

class ExitPanel {
    private String panelId;
    private ParkingLot parkingLot;
    private ParkingRate rate;

    public ExitPanel(String panelId, ParkingLot parkingLot, ParkingRate rate) {
        this.panelId = panelId;
        this.parkingLot = parkingLot;
        this.rate = rate;
    }

    public boolean processExit(ParkingTicket ticket, Payment payment) {
        System.out.println("[Exit " + panelId + "] Processing exit for: " + ticket.getTicketId());
        ticket.checkout();
        double fee = rate.calculateFee(ticket);
        System.out.printf("  Fee: $%.2f%n", fee);

        // Re-create payment with calculated amount if needed (simplified)
        boolean success = payment.processPayment();
        if (success) {
            parkingLot.releaseSpot(ticket);
            ticket.setStatus(ParkingTicketStatus.PAID);
            System.out.println("  Exit complete. Spot released.");
        }
        return success;
    }
}

// ─────────────────────────────────────────────
//  PARKING FLOOR
// ─────────────────────────────────────────────

class ParkingFloor {
    private String floorId;
    private List<ParkingSpot> spots;

    public ParkingFloor(String floorId) {
        this.floorId = floorId;
        this.spots = new ArrayList<>();
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
    }

    public ParkingSpot getFreeSpot(Vehicle vehicle) {
        for (ParkingSpot spot : spots) {
            if (spot.isFree() && spot.canFitVehicle(vehicle)) {
                return spot;
            }
        }
        return null;
    }

    public String getFloorId() { return floorId; }

    public void displayStatus() {
        System.out.println("  Floor " + floorId + ":");
        spots.forEach(s -> System.out.println("    " + s));
    }
}

// ─────────────────────────────────────────────
//  PARKING LOT  (Singleton)
// ─────────────────────────────────────────────

class ParkingLot {
    private static ParkingLot instance;

    private String name;
    private String address;
    private List<ParkingFloor> floors;
    private Map<String, ParkingTicket> activeTickets; // ticketId -> ticket

    private ParkingLot(String name, String address) {
        this.name = name;
        this.address = address;
        this.floors = new ArrayList<>();
        this.activeTickets = new HashMap<>();
    }

    // Singleton access
    public static synchronized ParkingLot getInstance(String name, String address) {
        if (instance == null) {
            instance = new ParkingLot(name, address);
        }
        return instance;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    // Find first available spot across all floors for the given vehicle
    public ParkingSpot getAvailableSpot(Vehicle vehicle) {
        for (ParkingFloor floor : floors) {
            ParkingSpot spot = floor.getFreeSpot(vehicle);
            if (spot != null) return spot;
        }
        return null;
    }

    // Park a vehicle: find spot, assign, and issue ticket
    public ParkingTicket parkVehicle(Vehicle vehicle) {
        ParkingSpot spot = getAvailableSpot(vehicle);
        if (spot == null) return null;

        spot.assignVehicle(vehicle);
        ParkingTicket ticket = new ParkingTicket(vehicle, spot);
        activeTickets.put(ticket.getTicketId(), ticket);
        return ticket;
    }

    // Release spot on exit
    public void releaseSpot(ParkingTicket ticket) {
        ticket.getSpot().removeVehicle();
        activeTickets.remove(ticket.getTicketId());
    }

    public void displayStatus() {
        System.out.println("=== " + name + " (" + address + ") ===");
        floors.forEach(ParkingFloor::displayStatus);
        System.out.println("  Active tickets: " + activeTickets.size());
    }
}

// ─────────────────────────────────────────────
//  DEMO DRIVER
// ─────────────────────────────────────────────

public class ParkingLot {

    public static void main(String[] args) throws InterruptedException {

        // ── Build the lot ─────────────────────────────────────
        ParkingLot lot = ParkingLot.getInstance("Central Park Lot", "123 Main St");
        ParkingRate rate = new ParkingRate(5.0); // $5/hr base

        ParkingFloor groundFloor = new ParkingFloor("G");
        groundFloor.addSpot(new CompactSpot("G-C1"));
        groundFloor.addSpot(new CompactSpot("G-C2"));
        groundFloor.addSpot(new LargeSpot("G-L1"));
        groundFloor.addSpot(new HandicappedSpot("G-H1"));
        groundFloor.addSpot(new MotorbikeSpot("G-M1"));

        ParkingFloor floor1 = new ParkingFloor("1");
        floor1.addSpot(new LargeSpot("1-L1"));
        floor1.addSpot(new LargeSpot("1-L2"));
        floor1.addSpot(new CompactSpot("1-C1"));

        lot.addFloor(groundFloor);
        lot.addFloor(floor1);

        // ── Panels ────────────────────────────────────────────
        EntrancePanel entrance = new EntrancePanel("E1", lot);
        ExitPanel exit = new ExitPanel("X1", lot, rate);

        // ── Simulate arrivals ─────────────────────────────────
        System.out.println("\n--- Vehicles Arriving ---");
        ParkingTicket t1 = entrance.admitVehicle(new Car("MH12AB1234", "Red"));
        ParkingTicket t2 = entrance.admitVehicle(new Truck("MH14XY5678", "Blue"));
        ParkingTicket t3 = entrance.admitVehicle(new Motorbike("MH01ZZ9999", "Black"));
        ParkingTicket t4 = entrance.admitVehicle(new Car("MH20CD3456", "White"));

        System.out.println("\n--- Lot Status After Arrivals ---");
        lot.displayStatus();

        // ── Simulate exits ────────────────────────────────────
        Thread.sleep(1000); // simulate time passing

        System.out.println("\n--- Vehicles Departing ---");
        if (t1 != null)
            exit.processExit(t1, new CashPayment(20.0, 20.0));

        if (t2 != null)
            exit.processExit(t2, new CardPayment(30.0, "4111111111111234"));

        System.out.println("\n--- Lot Status After Departures ---");
        lot.displayStatus();

        System.out.println("\n--- All active tickets ---");
        if (t3 != null) System.out.println(t3);
        if (t4 != null) System.out.println(t4);
    }
}
