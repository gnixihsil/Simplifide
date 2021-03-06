/*******************************************************************************
 * Copyright (c) 1995/2004 Simplifide, LLC.
 * All Right Reserved.
 ******************************************************************************/
package com.simplifide.core.source.design;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;

import com.simplifide.base.basic.struct.NodePosition;
import com.simplifide.base.core.module.InstanceModule;
import com.simplifide.base.core.module.InstanceModuleTop;
import com.simplifide.base.core.project.BuildInterface;
import com.simplifide.base.sourcefile.antlr.ParseDescriptor;
import com.simplifide.base.sourcefile.antlr.node.TopASTNode;
import com.simplifide.base.sourcefile.antlr.parse.ParseContext;
import com.simplifide.base.sourcefile.antlr.stream.ConnectionStore;
import com.simplifide.base.sourcefile.antlr.stream.TemplateContents;
import com.simplifide.base.sourcefile.antlr.stream.TemplateList;
import com.simplifide.base.sourcefile.util.EditorUtilities;
import com.simplifide.core.HardwareLog;
import com.simplifide.core.editors.SourceEditor;
import com.simplifide.core.project.EclipseBaseProject;
import com.simplifide.core.project.EclipseSuite;
import com.simplifide.core.python.template.TemplateInterpreter;
import com.simplifide.core.pythonext.basic.PythonSearch;
//import com.simplifide.core.scalaext.context.ScalaCurrentContext;

public abstract class FileTemplateHandler {

	public static String PORTINDENT   = "         ";
	public static String SIGNALINDENT = "                    ";
	
	private DesignFile designFile;
	private ConnectionStore storage;
	
	public FileTemplateHandler(DesignFile design) {
		this.setDesignFile(design);
		ParseDescriptor desc = this.getDesignFile().getDesignFileBuilder().getParseDescriptor();
		ParseContext context = desc.createContext();
		PythonSearch.getInstance().setReference(context.getRefHandler());
				
	}

	
	protected abstract String createAddition(String intermediate);
	protected abstract String executePython(String command,TemplateInterpreter interp);
	protected abstract String executeScala(String command, NodePosition pos);
	
	
	
	/*protected ScalaCurrentContext createCurrentContext(int pos) {
		EclipseBaseProject project = (EclipseBaseProject) this.getDesignFile().getProjectRef().getObject();
		EclipseSuite suite = (EclipseSuite) project.getSuiteReference().getObject();
		InstanceModule mod = null;
		if (this.getDesignFile().getEditor() != null) {
			InstanceModuleTop module = EditorUtilities.getEnclosingInstanceModule(this.designFile.getParseDescriptor(), 
					pos);
			if (module instanceof InstanceModuleTop) {
				mod = (InstanceModule) module;
			}
		}
		
		ScalaCurrentContext context = new ScalaCurrentContext(suite,project,this.getDesignFile(),mod);
		return context;
	}*/
	
	protected ParseContext getParseContext(TemplateContents cont) {
		ParseDescriptor desc = this.getDesignFile().getParseDescriptor();
		return EditorUtilities.getParseContext(desc, cont.getPosition().getStartPos()+1);
	}
	
	protected  Replace createReplaceValue(TemplateContents cont,  String repStr) {
		int epos = cont.getPosition().getEndPos();
		
		TopASTNode node = EditorUtilities.getPortListNode(this.getDesignFile().getParseDescriptor(), epos+10);
		Replace urep = new Replace(cont.getPosition().getStartPos(), cont.getPosition().getEndPos(),repStr);
		
		return urep;
	}
	
	/** Add all of the deletes to the template queue */
	private void addDeletes(MultiTextEdit multi, TemplateList list) {
		for (NodePosition pos : list.getDeleteList()) {
			ReplaceEdit rep = new ReplaceEdit(pos.getStartPos(),pos.getLength(),"");
			multi.addChild(rep);
		}
	}
	
	/** Actually performs the text edits to the files */
	private void applyMultiTextEdit(final IFile file, final MultiTextEdit multi, boolean save) {
		//final TextFileChange change = new TextFileChange(file.getName(),file);
		NullProgressMonitor nprog = new NullProgressMonitor();
		TextChange change = null;
		if (this.designFile.isOpened()) {
			change = new DocumentChange(this.designFile.getname(), this.designFile.getEditor().getDocument());
		}
		else {
			change = new TextFileChange(file.getName(),file);
			if (save) {
				((TextFileChange)change).setSaveMode(TextFileChange.FORCE_SAVE);
			}
		}
		try {
			change.setEdit(multi);
			change.perform(nprog);
		} catch (CoreException e) {
			HardwareLog.logError(e);
		}
		if (save && this.designFile.isOpened()) {
			//this.designFile.getEditor().doSave(nprog);
		}
		
	}
	
	/** Creates a list of templates by parsing files
	 * @todo there is no reason why this needs to be called 4 times rather than 1
	 * other than maybe getting the correct indexes
	 */
	private TemplateList createTemplateList() {
		/** Find a list of templates in the files
		 * 
		 */
		SourceEditor editor = this.getDesignFile().getEditor();
		Reader read;
		try {
			if (editor != null) { // If File is Open
				String str = editor.getDocument().get();
				read = new StringReader(str);
			}
			else { // Closed File
				read = new InputStreamReader(this.getDesignFile().getFileContents());
			}
			
			ParseDescriptor desc = this.getDesignFile().getDesignFileBuilder().getParseDescriptor();
			TemplateList tempList = this.getDesignFile().getParser().createTemplateList(read,desc);
			this.getDesignFile().getResource().touch(null);
			return tempList;

		}
		catch(CoreException e) {
			HardwareLog.logError(e);
		}
		return null;
	}
	
	/** Apply the python templates
	 * 
	 * @param list
	 * @param multi
	 */
	private void applyPythonTemplates(TemplateList list, MultiTextEdit multi) {
		for (TemplateContents cont : list.getContentList()) {
			
				ParseContext context = this.getParseContext(cont);
				TemplateInterpreter interp = new TemplateInterpreter(context.getRefHandler());
				
				String outval = "";
				if (cont.getType() == TemplateContents.PYTHON) {
					outval = this.executePython(cont.getText(),interp);
				}
				else {
					
					outval = this.executeScala(cont.getText(),cont.getPosition());
				}
				
				
				
				if (outval != null && outval.length() > 0) {
					String repval = "\n" + this.createAddition(outval) ;
					ReplaceEdit rep = new ReplaceEdit(cont.getPosition().getEndPos(),1,repval);
					multi.addChild(rep);
				
			}
		}
	}
	

	

	
	private void removeTemplates() {
		
		MultiTextEdit multi = new MultiTextEdit();
		TemplateList list = this.createTemplateList();
		this.addDeletes(multi, list);
		this.applyMultiTextEdit(this.getDesignFile().getIFile(), multi,true); 
	}
	
	private void createPythonAndScalaTemplates() {
		MultiTextEdit multi = new MultiTextEdit();
		TemplateList list = this.createTemplateList();
		this.applyPythonTemplates(list, multi);
		this.applyMultiTextEdit(this.getDesignFile().getIFile(), multi,true);
	}
	
	
	
	
	
	
	/* This is a tricky operation. 
	 * 1. Remove all of the templates 
	 * 2. Rebuild the File
	 * 3. Create the python templates and AUTOINST
	 * 4. Rebuild the File
	 * 5. Create the Auto Connections
	 * 6. Rebuild the File
	 * 7. Create the AutoArgs
	 * 8. Rebuild the File
	 */

	public void expandTemplates() {
		/**
		 * Operation which expands templates for the file
		 * 1. Build
		 * 2. Remove all of the templates
		 * 3. Build
		 * 4. Create Python and Emacs Templates (Not Sure Why Dual Use)
		 * 5. Build
		 * 6. Create Emacs Templates
		 * 7. Build
		 * 8. Create AutoArgs Templates
		 */
		try {
			this.getDesignFile().getResource().touch(null);
			this.designFile.getBuilder().build(BuildInterface.BUILD_FILE_OPEN); // 0
			this.removeTemplates();  // 1
			
			this.getDesignFile().getResource().touch(null);
			this.designFile.getBuilder().build(BuildInterface.BUILD_FILE_OPEN); // 2
			this.createPythonAndScalaTemplates(); // 3 
			this.getDesignFile().getEditor().doSave(null);
			
		} catch (CoreException e) {
			HardwareLog.logError(e);
		}
		
	
	}
	
	
	

	protected void setDesignFile(DesignFile designFile) {
		this.designFile = designFile;
	}


	protected DesignFile getDesignFile() {
		return designFile;
	}
	
	public void setStorage(ConnectionStore storage) {
		this.storage = storage;
	}

	public ConnectionStore getStorage() {
		return storage;
	}

	public static class Replace {
		public int start;
		public int end;
		public String text;
		
		public Replace(int start, int end, String text) {
			this.start = start;
			this.end = end;
			this.text = text;
		}
		
	}
	
	

}
