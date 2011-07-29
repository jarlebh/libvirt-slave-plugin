package hudson.plugins.libvirt;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapper.Environment;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

public class VMWareBuildWrapper extends BuildWrapper {      

    private String hypervisorDescription;
    private String virtualMachineName;
    
    @DataBoundConstructor
    public VMWareBuildWrapper(String hypervisorDescription, String virtualMachineName) {
        this.hypervisorDescription = hypervisorDescription;
        this.virtualMachineName = virtualMachineName;
    }
    
    public String getHypervisorDescription() {
        return hypervisorDescription;
    }

    public String getVirtualMachineName() {
        return virtualMachineName;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        VirtualMachineLauncher vmLauncher = new VirtualMachineLauncher(null, hypervisorDescription, virtualMachineName);
        vmLauncher.launchVM(listener.getLogger());
        return new Environment() {
            public boolean tearDown( AbstractBuild build, BuildListener listener ) throws IOException, InterruptedException {
                boolean result = true;
                VirtualMachineLauncher vmLauncher = new VirtualMachineLauncher(null, hypervisorDescription, virtualMachineName);
                try {
                    vmLauncher.powerOffVM(listener.getLogger());
                } catch (Exception e) {
                    listener.fatalError(e.getMessage());
                    result = false;
                }
                return result;
            }
        };
    }
    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(VMWareBuildWrapper.class);
        }

        @Override
        public String getDisplayName() {
            return "Start/stop virtual computer running on a virtualization platform";
        }

        public List<JenkinsVirtualMachine> getDefinedVirtualMachines(String hypervisorDescription) {
            List<JenkinsVirtualMachine> virtualMachinesList = new ArrayList<JenkinsVirtualMachine>();
            if (hypervisorDescription != null && !hypervisorDescription.equals("")) {
                Hypervisor hypervisor = null;
                for (Cloud cloud : Hudson.getInstance().clouds) {
                    if (cloud instanceof Hypervisor && ((Hypervisor) cloud).getHypervisorDescription().equals(hypervisorDescription)) {
                        hypervisor = (Hypervisor) cloud;
                        break;
                    }
                }
                virtualMachinesList.addAll(hypervisor.getVirtualMachines());
            }
            return virtualMachinesList;
        }

        public List<Hypervisor> getHypervisors() {
            List<Hypervisor> result = new ArrayList<Hypervisor>();
            for (Cloud cloud : Hudson.getInstance().clouds) {
                if (cloud instanceof Hypervisor) {
                    result.add((Hypervisor) cloud);
                }
            }
            return result;
        }        


        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

    }
}
