package itmo.backend.model.dto.drift;

import java.util.List;

public record VmConfigurationSnapshot(
  boolean found,
  String name,
  String hostname,
  Integer vcpu,
  Integer memoryMb,
  Integer diskSizeGb,
  String osImage,
  List<String> environmentPackages,
  String status
) {

  public static VmConfigurationSnapshot notFound() {
    return new VmConfigurationSnapshot(false, null, null, null, null, null, null, List.of(), null);
  }
}
