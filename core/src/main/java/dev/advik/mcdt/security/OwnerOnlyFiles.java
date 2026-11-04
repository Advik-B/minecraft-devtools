package dev.advik.mcdt.security;

import dev.advik.mcdt.Mcdt;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Writes files that only the current user can read: mode 0600 on POSIX systems, an owner-only ACL on Windows.
 * Permissions are applied before any content is written, and the file is moved into place atomically.
 */
public final class OwnerOnlyFiles {
	private OwnerOnlyFiles() {
	}

	public static void write(Path target, byte[] content) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path temp = createPrivateTempFile(dir, target.getFileName().toString());
		try {
			Files.write(temp, content);
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static Path createPrivateTempFile(Path dir, String name) throws IOException {
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			return Files.createTempFile(dir, "." + name, ".tmp",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		}
		Path temp = Files.createTempFile(dir, "." + name, ".tmp");
		restrictAcl(temp);
		return temp;
	}

	private static void restrictAcl(Path file) {
		AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
		if (view == null) {
			return;
		}
		try {
			UserPrincipal owner = view.getOwner();
			AclEntry ownerOnly = AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(owner)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.build();
			view.setAcl(List.of(ownerOnly));
		} catch (IOException | UnsupportedOperationException e) {
			// The user profile directory is already private on a default Windows install.
			Mcdt.LOGGER.warn("Could not restrict permissions on {}: {}", file, e.getMessage());
		}
	}
}
