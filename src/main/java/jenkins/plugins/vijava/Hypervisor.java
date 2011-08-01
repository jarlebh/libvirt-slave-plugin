/**
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Date: Mar 04, 2010
 * Author: Marco Mornati<mmornati@byte-code.com>
 */
package jenkins.plugins.vijava;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.VirtualMachine;

/**
 * Represents a virtual datacenter.
 */
public class Hypervisor extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(Hypervisor.class.getName());    
    private final String hypervisorHost;
    private final String hypervisorSystemUrl;
    private final int hypervisorSshPort;
    private final String username;
    private final String password;
    private transient List<JenkinsVirtualMachine> virtualMachineList = null;
    private transient ServiceInstance hypervisorConnection = null;
    private transient InventoryNavigator navigator = null;    
    @DataBoundConstructor
    public Hypervisor(String hypervisorHost, int hypervisorSshPort, String hypervisorSystemUrl, String username,
            String password) {
        super("Hypervisor(libvirt)");      
        this.hypervisorHost = hypervisorHost;
        if (hypervisorSystemUrl != null && !hypervisorSystemUrl.equals("")) {
            this.hypervisorSystemUrl = hypervisorSystemUrl;
        } else {
            this.hypervisorSystemUrl = "system";
        }
        this.hypervisorSshPort = hypervisorSshPort <= 0 ? 22 : hypervisorSshPort;
        this.username = username;
        this.password = password;
        virtualMachineList = retrieveVirtualMachines();
    }

    private static ServiceInstance makeConnection(String host, String user, String password, int port, String systemURL) throws VMWareException {
        ServiceInstance newConnection = null;
        URL hypervisorUri = null;
        try {
            hypervisorUri = constructHypervisorURI(host, user, port, systemURL);
            LOGGER.log(Level.INFO, "Trying to establish a connection to hypervisor URI: {0} as {1}/******",
                    new Object[] { hypervisorUri, user });
            newConnection = new ServiceInstance(hypervisorUri, user, password, true);
            LOGGER.log(Level.INFO, "Established connection to hypervisor URI: {0} as {1}/******", new Object[] { hypervisorUri, user });
        } catch (Exception e) {
            throw new VMWareException("Failed to connect to "+host, e);
        }
        return newConnection;
    }

    private List<JenkinsVirtualMachine> retrieveVirtualMachines() {
        List<JenkinsVirtualMachine> vmList = new ArrayList<JenkinsVirtualMachine>();
        try {
            Map<String, VirtualMachine> domains = getDomains();
            for (Entry<String, VirtualMachine> domain : domains.entrySet()) {
                vmList.add(new JenkinsVirtualMachine(this, domain.getKey()));
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return vmList;
    }

    public String getHypervisorHost() {
        return hypervisorHost;
    }

    public int getHypervisorSshPort() {
        return hypervisorSshPort;
    }

    public String getHypervisorSystemUrl() {
        return hypervisorSystemUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
    
    public String getHypervisorDescription() {
        return getHypervisorHost();
    }

    private ServiceInstance getConnection() throws VMWareException {
        if (hypervisorConnection == null) {
            hypervisorConnection = makeConnection(hypervisorHost, username, password, hypervisorSshPort,
                    hypervisorSystemUrl);
        }
        return hypervisorConnection;
    }

    public InventoryNavigator getRootNavigator(ServiceInstance instance) {
        if (navigator == null) {
            Folder rootFolder = hypervisorConnection.getRootFolder();
            String rootName = rootFolder.getName();
            System.out.println("root:" + rootName);
            navigator = new InventoryNavigator(rootFolder);
        }
        return navigator;
    }

    public VirtualMachine getDomain(String name) throws VMWareException {
        hypervisorConnection = getConnection();
        LogRecord info = new LogRecord(Level.INFO, "Getting hypervisor domain "+name);
        LOGGER.log(info);
        if (hypervisorConnection != null) {
            ManagedEntity mes;
            try {
                mes = getRootNavigator(hypervisorConnection).searchManagedEntity("VirtualMachine",name);
            } catch (Exception e) {
                throw new VMWareException("Failed to find domain "+name,e);
            }
            info = new LogRecord(Level.INFO, "Found "+mes+ " for "+name);
            LOGGER.log(info);
            return (VirtualMachine) mes;
        } else {
            return null;
        }
    }

    public Map<String, VirtualMachine> getDomains() throws VMWareException {
        Map<String, VirtualMachine> domains = new WeakHashMap<String, VirtualMachine>();
        hypervisorConnection = getConnection();
        LogRecord info = new LogRecord(Level.INFO, "Getting hypervisor domains");
        LOGGER.log(info);
        if (hypervisorConnection != null) {
            virtualMachineList = null;
            try {
                for (ManagedEntity c : getRootNavigator(hypervisorConnection).searchManagedEntities("VirtualMachine")) {
                    domains.put(c.getName(), (VirtualMachine) c);
                }
            } catch (Exception e) {
                throw new VMWareException("Failed to find domains ",e);
            }
        } else {
            LogRecord rec = new LogRecord(Level.SEVERE, "Cannot connect to datacenter {0} as {1}/******");
            rec.setParameters(new Object[] { hypervisorHost, username });
            LOGGER.log(rec);
        }

        return domains;
    }

    public List<JenkinsVirtualMachine> getVirtualMachines() {
        if (virtualMachineList == null) {
            virtualMachineList = retrieveVirtualMachines();
        }
        return virtualMachineList;
    }

    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int i) {
        return Collections.emptySet();
    }

    public boolean canProvision(Label label) {
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Hypervisor");
        sb.append("{hypervisorUri='").append(hypervisorHost).append('\'');
        sb.append(", username='").append(username).append('\'');
        sb.append('}');
        return sb.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public static URL constructHypervisorURI(String host, String user, int sshPort, String systemUrl) throws MalformedURLException {
        return new URL("https", host, "/sdk");
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        public final ConcurrentMap<String, Hypervisor> hypervisors = new ConcurrentHashMap<String, Hypervisor>();      
        private String hypervisorHost;
        private String hypervisorSystemUrl;
        private int hypervisorSshPort;
        private String username;
        private String password;

        public String getDisplayName() {
            return "Hypervisor (via libvirt)";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            
            hypervisorHost = o.getString("hypervisorHost");
            hypervisorSystemUrl = o.getString("hypervisorSystemUrl");
            hypervisorSshPort = o.getInt("hypervisorSshPort");
            username = o.getString("username");
            password = o.getString("password");
            save();
            return super.configure(req, o);
        }

        public FormValidation doTestConnection(@QueryParameter String hypervisorHost,
                @QueryParameter String hypervisorSshPort, @QueryParameter String username, @QueryParameter String password,
                @QueryParameter String hypervisorSystemUrl) throws Exception, ServletException {
            try {
                if (hypervisorHost == null) {
                    return FormValidation.error("Hypervisor Host is not specified");
                }                
                if (username == null) {
                    return FormValidation.error("Username is not specified");
                }

                LogRecord rec = new LogRecord(Level.INFO, "Testing connection to hypervisor: {0}");
                int port = -1;
                if (hypervisorSshPort != null) {
                    port = Integer.valueOf(hypervisorSshPort);
                }
                rec.setParameters(new Object[] { constructHypervisorURI(hypervisorHost, username, port, hypervisorSystemUrl) });
                LOGGER.log(rec);
                ServiceInstance testHypervisorConnection = makeConnection(hypervisorHost, username, password, port,
                        hypervisorSystemUrl);
                return FormValidation.ok("Connected successfully");
            } catch (Exception e) {
                LogRecord rec = new LogRecord(Level.WARNING, "Failed to check hypervisor connection to {0} as {1}/******");
                rec.setThrown(e);
                rec.setParameters(new Object[] { hypervisorHost, username });
                LOGGER.log(rec);
                return FormValidation.error(e.getMessage());
            } catch (UnsatisfiedLinkError e) {
                LogRecord rec = new LogRecord(Level.WARNING,
                        "Failed to connect to hypervisor. Check libvirt installation on hudson machine!");
                rec.setThrown(e);
                rec.setParameters(new Object[] { hypervisorHost, username });
                LOGGER.log(rec);
                return FormValidation.error(e.getMessage());
            }
        }

        public String getHypervisorHost() {
            return hypervisorHost;
        }

        public int getHypervisorSshPort() {
            return hypervisorSshPort;
        }

        public String getHypervisorSystemUrl() {
            return hypervisorSystemUrl;
        }
      
        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
        
    }

    public void stop() {
        if (hypervisorConnection != null) {
            if (hypervisorConnection.getServerConnection() != null) {
                hypervisorConnection.getServerConnection().logout();
            }
            hypervisorConnection = null;

        }

    }
}
