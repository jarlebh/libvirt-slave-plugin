package jenkins.plugins.vijava;

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
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class VMWareBuildWrapper extends BuildWrapper {      

    private String hypervisorDescription;
    private String virtualMachineName;
    private Boolean stopWhenFinished;
    @DataBoundConstructor
    public VMWareBuildWrapper(String hypervisorDescription, String virtualMachineName, Boolean stopWhenFinished) {
        this.hypervisorDescription = hypervisorDescription;
        this.virtualMachineName = virtualMachineName;
        this.stopWhenFinished = stopWhenFinished;
    }
    
    public String getHypervisorDescription() {
        return hypervisorDescription;
    }

    public String getVirtualMachineName() {
        return virtualMachineName;
    }
    public Boolean getStopWhenFinished() {
        return stopWhenFinished;
    }
    public void setHypervisorDescription(String hypervisorDescription) {
        this.hypervisorDescription = hypervisorDescription;
    }

    public void setVirtualMachineName(String virtualMachineName) {
        this.virtualMachineName = virtualMachineName;
    }


    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        VirtualMachineLauncher vmLauncher = new VirtualMachineLauncher(null, hypervisorDescription, virtualMachineName);
        vmLauncher.launchVM(listener.getLogger());
        return new Environment() {
            public boolean tearDown( AbstractBuild build, BuildListener listener ) throws IOException, InterruptedException {
                boolean result = true;
                if (stopWhenFinished) {
                    VirtualMachineLauncher vmLauncher = new VirtualMachineLauncher(null, hypervisorDescription, virtualMachineName);
                    try {
                        vmLauncher.powerOffVM(listener.getLogger());
                    } catch (Exception e) {
                        listener.fatalError(e.getMessage());
                        result = false;
                    }
                }
                return result;
            }
        };
    }
    @Extension(ordinal=999)
    public static class DescriptorImpl extends BuildWrapperDescriptor {
               
        public DescriptorImpl() {
            super(VMWareBuildWrapper.class);
        }

        @Override
        public String getDisplayName() {
            return "Start/stop virtual computer running on a virtualization platform";
        }

        public ListBoxModel doFillHypervisorDescriptionItems() {
            ListBoxModel model = new ListBoxModel();            
            for (Hypervisor vis : getHypervisors()) {
                model.add(vis.getHypervisorDescription(),vis.getHypervisorDescription());
            }
            return model;
        }
         
        public ListBoxModel doFillVirtualMachineNameItems(@QueryParameter String hypervisorDescription) {
            ListBoxModel model = new ListBoxModel();                      
             for (JenkinsVirtualMachine mac : getDefinedVirtualMachines(hypervisorDescription)) {
                 model.add(mac.getDisplayName(), mac.getName());
             }
             return model;
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